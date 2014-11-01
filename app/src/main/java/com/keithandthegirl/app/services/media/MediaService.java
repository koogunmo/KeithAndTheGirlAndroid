package com.keithandthegirl.app.services.media;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;
import android.widget.Toast;

import com.keithandthegirl.app.R;
import com.keithandthegirl.app.db.model.EpisodeConstants;
import com.keithandthegirl.app.db.model.EpisodeInfoHolder;
import com.keithandthegirl.app.ui.episode.EpisodeActivity;

import java.io.File;
import java.io.IOException;

/**
 * Service that handles media playback. This is the Service through which we perform all the media
 * handling in our application. It waits for Intents, which signal the service to perform specific operations: Play, Pause,
 * Rewind, Skip, etc.
 */
public class MediaService extends Service implements OnCompletionListener, OnPreparedListener, OnErrorListener, AudioFocusable {

    // The tag we put on debug messages
    final static String TAG = MediaService.class.getSimpleName();

    // The id of the episode to play
    public static final String EXTRA_EPISODE_ID = "com.keithandthegirl.app.services.media.extra.EPISODE_ID";

    public static final String EXTRA_SEEK_POSITION = "com.keithandthegirl.app.services.media.extra.SEEK_POSITION";

    // These are the Intent actions that we are prepared to handle. Notice that the fact these
    // constants exist in our class is a mere convenience: what really defines the actions our
    // service can handle are the <action> tags in the <intent-filters> tag for our service in
    // AndroidManifest.xml.
    public static final String ACTION_TOGGLE_PLAYBACK = "com.keithandthegirl.app.services.media.action.TOGGLE_PLAYBACK";
    public static final String ACTION_PLAY = "com.keithandthegirl.app.services.media.action.PLAY";
    public static final String ACTION_PAUSE = "com.keithandthegirl.app.services.media.action.PAUSE";
    public static final String ACTION_STOP = "com.keithandthegirl.app.services.media.action.STOP";
    public static final String ACTION_SKIP = "com.keithandthegirl.app.services.media.action.SKIP";
    public static final String ACTION_FASTFORWARD = "com.keithandthegirl.app.services.media.action.FASTFORWARD";
    public static final String ACTION_REWIND = "com.keithandthegirl.app.services.media.action.REWIND";
    public static final String ACTION_PREVIOUS = "com.keithandthegirl.app.services.media.action.PREVIOUS";
    public static final String ACTION_SEEK = "com.keithandthegirl.app.services.media.action.SEEK";
    public static final String ACTION_URL = "com.keithandthegirl.app.services.media.action.URL";
    public static final String ACTION_STATUS = "com.keithandthegirl.app.services.media.action.STATUS";

    public static final String EVENT_STATUS = "com.keithandthegirl.app.services.media.event.STATUS";

    // The state of the media player
    public static final String EXTRA_STATE = "com.keithandthegirl.app.services.media.action.STATE";
    public static final String EXTRA_CURRENT_POSITION = "com.keithandthegirl.app.services.media.action.CURRENT_POSITION";
    public static final String EXTRA_PLAYED_POSITION = "com.keithandthegirl.app.services.media.action.PLAYED_POSITION";
    public static final String EXTRA_DURATION = "com.keithandthegirl.app.services.media.action.DURATION";
    public static final String EXTRA_TITLE = "com.keithandthegirl.app.services.media.action.TITLE";

    // The volume we set the media player to when we lose audio focus, but are allowed to reduce
    // the volume instead of stopping playback.
    public static final float DUCK_VOLUME = 0.1f;

    // The amount if time (in ms) to fast forward and rewind
    private static final int REW_JUMP_MILLISEC = 10000;
    private static final int FF_JUMP_MILLISEC = 30000;

    // our media player
    MediaPlayer mPlayer = null;

    // our AudioFocusHelper object, if it's available (it's available on SDK level >= 8)
    // If not available, this will be null. Always check for null before using!
    AudioFocusHelper mAudioFocusHelper = null;

    // indicates the state our service:
    public enum State {
        Retrieving, // the MediaRetriever is retrieving music
        Stopped,    // media player is stopped and not prepared to play
        Preparing,  // media player is preparing...
        Playing,    // playback active (media player ready!). (but the media player may actually be
                    // paused in this state if we don't have audio focus. But we stay in this state
                    // so that we know we have to resume playback once we get focus back)
        Paused      // playback paused (media player ready!)
    };
    State mState = State.Retrieving;

    // if in Retrieving mode, this flag indicates whether we should start playing immediately
    // when we are ready or not.
    boolean mStartPlayingAfterRetrieve = false;

    // if mStartPlayingAfterRetrieve is true, this variable indicates the URL that we should
    // start playing when we are ready. If null, we should play a random song from the device
    Uri mWhatToPlayAfterRetrieve = null;
    enum PauseReason {
        UserRequest,  // paused by user request
        FocusLoss,    // paused because of audio focus loss
    };

    // why did we pause? (only relevant if mState == State.Paused)
    PauseReason mPauseReason = PauseReason.UserRequest;

    // do we have audio focus?
    enum AudioFocus {
        NoFocusNoDuck,    // we don't have audio focus, and can't duck
        NoFocusCanDuck,   // we don't have focus, but can play at a low volume ("ducking")
        Focused           // we have full audio focus
    }
    AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

    Long mEpisodeId;
    EpisodeInfoHolder mEpisodeHolder;

    // title of the song we are currently playing
//    String mSongTitle = "";

    // whether the song we are playing is streaming from the network
    boolean mIsStreaming = false;

    // Wifi lock that we hold when streaming files from the internet, in order to prevent the
    // device from shutting off the Wifi radio
    WifiLock mWifiLock;

    // The ID we use for the notification (the onscreen alert that appears at the notification
    // area at the top of the screen as an icon -- and as text as well if the user expands the
    // notification area).
    final int NOTIFICATION_ID = 2;

    // our RemoteControlClient object, which will use remote control APIs available in
    // SDK level >= 14, if they're available.
    RemoteControlClient mRemoteControlClientCompat;

    // Dummy album art we will pass to the remote control (if the APIs are available).
    Bitmap mDummyAlbumArt;

    // The component name of MediaIntentReceiver, for use with media button and remote control
    // APIs
    ComponentName mMediaButtonReceiverComponent;
    AudioManager mAudioManager;
    NotificationManager mNotificationManager;
    Notification mNotification = null;

    /**
     * Makes sure the media player exists and has been reset. This will create the media player
     * if needed, or reset the existing media player if one already exists.
     */
    void createMediaPlayerIfNeeded() {

        if (mPlayer == null) {
            mPlayer = new MediaPlayer();

            // Make sure the media player will acquire a wake-lock while playing. If we don't do
            // that, the CPU might go to sleep while the song is playing, causing playback to stop.
            //
            // Remember that to use this, we have to declare the android.permission.WAKE_LOCK
            // permission in AndroidManifest.xml.
            mPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

            // we want the media player to notify us when it's ready preparing, and when it's done
            // playing:
            mPlayer.setOnPreparedListener(this);
            mPlayer.setOnCompletionListener(this);
            mPlayer.setOnErrorListener(this);
        } else
            mPlayer.reset();
    }

    @Override
    public void onCreate() {
//        Log.i(TAG, "onCreate : enter");

        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // create the Audio Focus Helper, if the Audio Focus feature is available (SDK 8 or above)
        if (android.os.Build.VERSION.SDK_INT >= 8)
            mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(), this);
        else
            mAudioFocus = AudioFocus.Focused; // no focus feature, so we always "have" audio focus

        mDummyAlbumArt = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);

        mMediaButtonReceiverComponent = new ComponentName(this, MediaIntentReceiver.class);

//        Log.i(TAG, "onCreate : exit");
    }

    /**
     * Called when we receive an Intent. When we receive an intent sent to us via startService(),
     * this is the method that gets called. So here we react appropriately depending on the
     * Intent's action, which specifies what is being requested of us.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
//        Log.i(TAG, "onStartCommand : enter");

        String action = intent.getAction();
//        Log.i(TAG, "onStartCommand : action=" + action);
        if (action.equals(ACTION_TOGGLE_PLAYBACK)) processTogglePlaybackRequest();
        else if (action.equals(ACTION_PLAY)) processPlayRequest();
        else if (action.equals(ACTION_PAUSE)) processPauseRequest();
        else if (action.equals(ACTION_FASTFORWARD)) processFastForwardRequest();
        else if (action.equals(ACTION_SKIP)) processSkipRequest();
        else if (action.equals(ACTION_STOP)) processStopRequest();
        else if (action.equals(ACTION_REWIND)) processRewindRequest();
        else if (action.equals(ACTION_PREVIOUS)) processPreviousRequest();
        else if (action.equals(ACTION_SEEK)) processSeekRequest(intent);
        else if (action.equals(ACTION_URL)) processAddRequest(intent);
        else if (action.equals(ACTION_STATUS) ) processStatusRequest();

//        Log.i(TAG, "onStartCommand : exit");
        return START_NOT_STICKY; // Means we started the service, but don't want it to
                                 // restart in case it's killed.
    }

    void processSeekRequest(Intent intent) {
//        Log.d(TAG, "processSeekRequest : enter");

        if( null != mPlayer ) {
            int seekPosition = intent.getIntExtra(EXTRA_SEEK_POSITION, -1);

            mPlayer.seekTo(seekPosition);

            updateLastPlayed(seekPosition);

            processStatusRequest();
        }

//        Log.d(TAG, "processSeekRequest : exit");
    }

    void processStatusRequest() {

        if( null == mEpisodeId || mEpisodeId.longValue() <= 0 ) {
            return;
        }

        Intent broadcast = new Intent( EVENT_STATUS );
        broadcast.putExtra(EXTRA_EPISODE_ID, mEpisodeId);
        broadcast.putExtra(EXTRA_TITLE, mEpisodeHolder.getShowPrefix() + " : " + mEpisodeHolder.getEpisodeNumber() + " - " + mEpisodeHolder.getEpisodeTitle() );
        broadcast.putExtra(EXTRA_STATE, mState.name() );

        if( null != mPlayer && ( mState.equals( State.Playing ) || mState.equals( State.Paused ) ) ) {

            final int HOUR = 60*60*1000;
            final int MINUTE = 60*1000;
            final int SECOND = 1000;

            int durationInMillis = mPlayer.getDuration();
            int curVolume = mPlayer.getCurrentPosition();

            int durationHour = durationInMillis/HOUR;
            int durationMint = (durationInMillis%HOUR)/MINUTE;
            int durationSec = (durationInMillis%MINUTE)/SECOND;

            int currentHour = curVolume/HOUR;
            int currentMint = (curVolume%HOUR)/MINUTE;
            int currentSec = (curVolume%MINUTE)/SECOND;

            String played = "00:00", duration = "00:00";

            if( durationHour > 0 ) {
                played = String.format( "%02d:%02d:%02d", currentHour,currentMint,currentSec );
                duration = String.format( "%02d:%02d:%02d", durationHour,durationMint,durationSec );
            } else {
                played = String.format( "%02d:%02d", currentMint,currentSec );
                duration = String.format( "%02d:%02d", durationMint,durationSec );
            }

            broadcast.putExtra(EXTRA_CURRENT_POSITION, mPlayer.getCurrentPosition());
            broadcast.putExtra(EXTRA_PLAYED_POSITION, played );
            broadcast.putExtra(EXTRA_DURATION, duration );
        }

        sendBroadcast( broadcast );

    }

    void processTogglePlaybackRequest() {
//        Log.d(TAG, "processTogglePlaybackRequest : enter");

        if (mState == State.Paused || mState == State.Stopped) {
            processPlayRequest();
        } else {
            processPauseRequest();
        }

//        Log.d(TAG, "processTogglePlaybackRequest : exit");
    }

    void processPlayRequest() {
//        Log.d(TAG, "processPlayRequest : enter");

        if( null == mPlayer ) {
            return;
        }

        if (mState == State.Retrieving) {
            // If we are still retrieving media, just set the flag to start playing when we're
            // ready
            mWhatToPlayAfterRetrieve = null; // play a random song
            mStartPlayingAfterRetrieve = true;

//            Log.d(TAG, "processPlayRequest : exit, retrieving");
            return;
        }

        tryToGetAudioFocus();

        // actually play the song
        if (mState == State.Stopped) {
            // If we're stopped, just go ahead to the next song and start playing
            playEpisode();
        } else if (mState == State.Paused) {
            // If we're paused, just continue playback and restore the 'foreground service' state.
            mState = State.Playing;
            setUpAsForeground(mEpisodeHolder.getEpisodeTitle() + " (playing)");
            configAndStartMediaPlayer();
        }

        // Tell any remote controls that our playback state is 'playing'.
        if (mRemoteControlClientCompat != null) {
            mRemoteControlClientCompat.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
        }

        processStatusRequest();

//        Log.d(TAG, "processPlayRequest : exit");
    }

    void processPauseRequest() {
//        Log.d(TAG, "processPauseRequest : enter");

        if( null == mPlayer ) {
            return;
        }

        if (mState == State.Retrieving) {
            // If we are still retrieving media, clear the flag that indicates we should start
            // playing when we're ready
            mStartPlayingAfterRetrieve = false;

//            Log.d(TAG, "processPauseRequest : exit, retrieving");
            return;
        }

        if (mState == State.Playing) {
            // Pause media player and cancel the 'foreground service' state.
            mState = State.Paused;
            mPlayer.pause();
            relaxResources(false); // while paused, we always retain the MediaPlayer
            // do not give up audio focus
        }

        // Tell any remote controls that our playback state is 'paused'.
        if (mRemoteControlClientCompat != null) {
            mRemoteControlClientCompat.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
        }

        processStatusRequest();

//        Log.d(TAG, "processPauseRequest : exit");
    }

    void processPreviousRequest() {
//        Log.d(TAG, "processPreviousRequest : enter");

        if( null == mPlayer ) {
            return;
        }

        if (mState == State.Playing || mState == State.Paused)
            mPlayer.seekTo(0);

        processStatusRequest();

//        Log.d(TAG, "processPreviousRequest : exit");
    }

    void processRewindRequest() {
//        Log.d(TAG, "processRewindRequest : enter");

        if( null == mPlayer ) {
            return;
        }

        if (mState == State.Playing || mState == State.Paused) {

            int currentPosition = mPlayer.getCurrentPosition() - REW_JUMP_MILLISEC;

            if(currentPosition >= 0) {
                mPlayer.seekTo(currentPosition);
            } else {
                mPlayer.seekTo(0);
            }
        }

        processStatusRequest();

//        Log.d(TAG, "processRewindRequest : exit");
    }

    void processFastForwardRequest() {
//        Log.d(TAG, "processFastForwardRequest : enter");

        if( null == mPlayer ) {
            return;
        }

        if (mState == State.Playing || mState == State.Paused) {
            tryToGetAudioFocus();

            int currentPosition = mPlayer.getCurrentPosition() + FF_JUMP_MILLISEC;

            if(currentPosition < mPlayer.getDuration()) {
                mPlayer.seekTo(currentPosition);
            } else {
                mPlayer.seekTo(mPlayer.getDuration());
            }
        }

        processStatusRequest();

//        Log.d(TAG, "processFastForwardRequest : exit");
    }

    void processSkipRequest() {
//        Log.d(TAG, "processSkipRequest : enter");

        if (mState == State.Playing || mState == State.Paused) {
            tryToGetAudioFocus();
//            playNextSong(null);
        }

        processStatusRequest();

//        Log.d(TAG, "processSkipRequest : exit");
    }

    void processStopRequest() {
//        Log.d(TAG, "processStopRequest : enter");

        processStopRequest(false);

//        Log.d(TAG, "processStopRequest : exit");
    }

    void processStopRequest(boolean force) {
//        Log.d(TAG, "processStopRequest : enter");

        if( null == mPlayer ) {
            return;
        }

        if (mState == State.Playing || mState == State.Paused || force) {
            mState = State.Stopped;

            // let go of all resources...
            relaxResources(true);
            giveUpAudioFocus();

            // Tell any remote controls that our playback state is 'paused'.
            if (mRemoteControlClientCompat != null) {
                mRemoteControlClientCompat
                        .setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
            }

            processStatusRequest();

//            Log.d(TAG, "processStopRequest : exit, stopping service");
            // service is no longer necessary. Will be started again if needed.
            if( force ) {
                stopSelf();
            }
        }

//        Log.d(TAG, "processStopRequest : exit");
    }

    /**
     * Releases resources used by the service for playback. This includes the "foreground service"
     * status and notification, the wake locks and possibly the MediaPlayer.
     *
     * @param releaseMediaPlayer Indicates whether the Media Player should also be released or not
     */
    void relaxResources(boolean releaseMediaPlayer) {
//        Log.d(TAG, "relaxResources : enter");

        timerHandler.removeCallbacks(timerRunnable);

        // stop being a foreground service
        stopForeground(true);

        // stop and release the Media Player, if it's available
        if (releaseMediaPlayer && mPlayer != null) {
            mPlayer.reset();
            mPlayer.release();
            mPlayer = null;
        }

        // we can also release the Wifi lock, if we're holding it
        if (mWifiLock.isHeld()) mWifiLock.release();

//        Log.d(TAG, "relaxResources : exit");
    }

    void giveUpAudioFocus() {
//        Log.d(TAG, "giveUpAudioFocus : enter");

        if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
                && mAudioFocusHelper.abandonFocus())
            mAudioFocus = AudioFocus.NoFocusNoDuck;

//        Log.d(TAG, "giveUpAudioFocus : exit");
    }

    /**
     * Reconfigures MediaPlayer according to audio focus settings and starts/restarts it. This
     * method starts/restarts the MediaPlayer respecting the current audio focus state. So if
     * we have focus, it will play normally; if we don't have focus, it will either leave the
     * MediaPlayer paused or set it to a low volume, depending on what is allowed by the
     * current focus settings. This method assumes mPlayer != null, so if you are calling it,
     * you have to do so from a context where you are sure this is the case.
     */
    void configAndStartMediaPlayer() {
//        Log.d(TAG, "configAndStartMediaPlayer : enter");

        if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
            // If we don't have audio focus and can't duck, we have to pause, even if mState
            // is State.Playing. But we stay in the Playing state so that we know we have to resume
            // playback once we get the focus back.
            if (mPlayer.isPlaying()) mPlayer.pause();

//            Log.d(TAG, "configAndStartMediaPlayer : exit, no focus no duck");
            return;
        }
        else if (mAudioFocus == AudioFocus.NoFocusCanDuck)
            mPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME);  // we'll be relatively quiet
        else
            mPlayer.setVolume(1.0f, 1.0f); // we can be loud

        if( mEpisodeHolder.getEpisodeLastPlayed() > 0 ) {
            mPlayer.seekTo( mEpisodeHolder.getEpisodeLastPlayed() );
        }

        if (!mPlayer.isPlaying()) {
            mPlayer.start();
        }

        timerHandler.postDelayed(timerRunnable, 0);

//        Log.d(TAG, "configAndStartMediaPlayer : exit");
    }

    void processAddRequest(Intent intent) {
//        Log.d(TAG, "processAddRequest : enter");

        if (mState == State.Playing || mState == State.Paused) {

            processStopRequest();

        }

        mEpisodeId = intent.getLongExtra( EXTRA_EPISODE_ID, -1 );
        mEpisodeHolder = EpisodeInfoHolder.loadEpisode( this, mEpisodeId );

        mState = State.Stopped;

        tryToGetAudioFocus();
        playEpisode();

//        Log.d(TAG, "processAddRequest : exit");
    }

    void tryToGetAudioFocus() {
//        Log.d(TAG, "tryToGetAudioFocus : enter");

        if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
                && mAudioFocusHelper.requestFocus())
            mAudioFocus = AudioFocus.Focused;

//        Log.d(TAG, "tryToGetAudioFocus : exit");
    }

    /**
     * Starts playing the next song. If manualUrl is null, the next song will be randomly selected
     * from our Media Retriever (that is, it will be a random song in the user's device). If
     * manualUrl is non-null, then it specifies the URL or path to the song that will be played
     * next.
     */
    void playEpisode() {
//        Log.d(TAG, "playEpisode : enter");

        mState = State.Stopped;
        relaxResources(false); // release everything except MediaPlayer

        if( null == mEpisodeHolder ) {
            processStopRequest(true); // stop everything!

            return;
        }

        mIsStreaming = !mEpisodeHolder.isEpisodeDownloaded(); // playing a locally available song

        try {
            createMediaPlayerIfNeeded();

            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            if( mEpisodeHolder.isEpisodeDownloaded() ) {
                File externalFile = new File(getExternalFilesDir(null), mEpisodeHolder.getEpisodeFilename());
                mPlayer.setDataSource(Uri.fromFile(externalFile).toString());
            } else {
                mPlayer.setDataSource(mEpisodeHolder.getEpisodeFileUrl());
            }

            mState = State.Preparing;
            setUpAsForeground(mEpisodeHolder.getEpisodeTitle() + " (loading)");

            // Use the media button APIs (if available) to register ourselves for media button
            // events
            MediaButtonHelper.registerMediaButtonEventReceiverCompat(mAudioManager, mMediaButtonReceiverComponent);

            // Use the remote control APIs (if available) to set the playback state
            if (mRemoteControlClientCompat == null) {
                Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                mediaButtonIntent.setComponent(mMediaButtonReceiverComponent);
                mRemoteControlClientCompat = new RemoteControlClient(
                        PendingIntent.getBroadcast(this /*context*/,
                                0 /*requestCode, ignored*/, mediaButtonIntent /*intent*/, 0 /*flags*/));
                RemoteControlHelper.registerRemoteControlClient(mAudioManager, mRemoteControlClientCompat);
            }

            mRemoteControlClientCompat.setPlaybackState(
                    RemoteControlClient.PLAYSTATE_PLAYING);

            mRemoteControlClientCompat.setTransportControlFlags(
                    RemoteControlClient.FLAG_KEY_MEDIA_PLAY |
                            RemoteControlClient.FLAG_KEY_MEDIA_PAUSE |
                            RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS |
                            RemoteControlClient.FLAG_KEY_MEDIA_NEXT);

            // Update the remote controls
            mRemoteControlClientCompat.editMetadata(true)
                    .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, mEpisodeHolder.getShowName())
                    .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, mEpisodeHolder.getShowPrefix() + " : " + mEpisodeHolder.getEpisodeNumber())
                    .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, mEpisodeHolder.getEpisodeTitle())
                    .putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, mEpisodeHolder.getEpisodeLength())
                            // TODO: fetch real item artwork
                    .putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, mDummyAlbumArt)
                    .apply();

            // starts preparing the media player in the background. When it's done, it will call
            // our OnPreparedListener (that is, the onPrepared() method on this class, since we set
            // the listener to 'this').
            //
            // Until the media player is prepared, we *cannot* call start() on it!
            mPlayer.prepareAsync();

            // If we are streaming from the internet, we want to hold a Wifi lock, which prevents
            // the Wifi radio from going to sleep while the song is playing. If, on the other hand,
            // we are *not* streaming, we want to release the lock if we were holding it before.
            if (mIsStreaming) mWifiLock.acquire();
            else if (mWifiLock.isHeld()) mWifiLock.release();
        }
        catch (IOException ex) {
            Log.e(TAG, "IOException playing next song: " + ex.getMessage());
            ex.printStackTrace();
        }

//        Log.d(TAG, "playEpisode : exit");
    }

    /** Called when media player is done playing current song. */
    public void onCompletion(MediaPlayer player) {
//        Log.d(TAG, "onCompletion : enter");
        // The media player finished playing the current song, so we go ahead and start the next.
//        playNextSong(null);
        processStopRequest(true);

//        Log.d(TAG, "onCompletion : exit");
    }

    /** Called when media player is done preparing. */
    public void onPrepared(MediaPlayer player) {
//        Log.d(TAG, "onPrepared : enter");

//        try {
//            Thread.sleep( 2000 );
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }

        // The media player is done preparing. That means we can start playing!
        mState = State.Playing;
        updateNotification(mEpisodeHolder.getEpisodeTitle() + " (playing)");
        configAndStartMediaPlayer();

        processStatusRequest();

//        Log.d(TAG, "onPrepared : exit");
    }

    /** Updates the notification. */
    void updateNotification(String text) {
//        Log.v(TAG, "updateNotification : enter");

        Intent episodeIntent = new Intent( getApplicationContext(), EpisodeActivity.class );
        episodeIntent.putExtra( EpisodeActivity.EPISODE_KEY, mEpisodeId );

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(getApplicationContext());
        stackBuilder.addParentStack(EpisodeActivity.class);
        stackBuilder.addNextIntent( episodeIntent );
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent( 0, PendingIntent.FLAG_UPDATE_CURRENT );

        mNotification = new NotificationCompat
                .Builder( getApplicationContext() )
                .setSmallIcon( R.drawable.ic_launcher )
                .setContentTitle( "Playing " + mEpisodeHolder.getShowPrefix() + " : " + mEpisodeHolder.getEpisodeNumber() )
                .setContentText( mEpisodeHolder.getEpisodeTitle() )
                .setOngoing( true )
                .setContentIntent( resultPendingIntent )
//                .addAction(
//                        R.drawable.,
//                        "New",
//                        getPendingIntent(this.getShowAdvertisingIntent(this.getLaunchNewGmailEmailIntent(labelData)))
//                )
//                .addAction(
//                        R.drawable.ic_menu_preferences,
//                        getString(R.string.notification_action_settings_title),
//                        getPendingIntent(this.getLaunchSettingsIntent()))
                .build();

        mNotificationManager.notify(NOTIFICATION_ID, mNotification);

//        Log.v(TAG, "updateNotification : exit");
    }

    /**
     * Configures service as a foreground service. A foreground service is a service that's doing
     * something the user is actively aware of (such as playing music), and must appear to the
     * user as a notification. That's why we create the notification here.
     */
    void setUpAsForeground(String text) {
//        Log.v(TAG, "setUpAsForeground : enter");

        Intent episodeIntent = new Intent( getApplicationContext(), EpisodeActivity.class );
        episodeIntent.putExtra( EpisodeActivity.EPISODE_KEY, mEpisodeId );

        TaskStackBuilder stackBuilder = TaskStackBuilder.create( getApplicationContext() );
        stackBuilder.addParentStack( EpisodeActivity.class );
        stackBuilder.addNextIntent( episodeIntent );
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent( 0, PendingIntent.FLAG_UPDATE_CURRENT );

        mNotification = new NotificationCompat
                .Builder( getApplicationContext() )
                .setSmallIcon( R.drawable.ic_launcher )
                .setContentTitle( "Playing " + mEpisodeHolder.getShowPrefix() + " : " + mEpisodeHolder.getEpisodeNumber() )
                .setContentText(mEpisodeHolder.getEpisodeTitle())
                .setOngoing(true)
                .setContentIntent( resultPendingIntent )
//                .addAction(
//                        R.drawable.,
//                        "New",
//                        getPendingIntent(this.getShowAdvertisingmEpisodeHolder.getEpisodeIntent(this.getLaunchNewGmailEmailIntent(labelData)))
//                )
//                .addAction(
//                        R.drawable.ic_menu_preferences,
//                        getString(R.string.notification_action_settings_title),
//                        getPendingIntent(this.getLaunchSettingsIntent()))
                .build();

        startForeground(NOTIFICATION_ID, mNotification);

//        Log.v(TAG, "setUpAsForeground : exit");
    }

    /**
     * Called when there's an error playing media. When this happens, the media player goes to
     * the Error state. We warn the user about the error and reset the media player.
     */
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Toast.makeText(getApplicationContext(), "KATG player error! Resetting.", Toast.LENGTH_SHORT).show();

        Log.e(TAG, "Error: what=" + String.valueOf(what) + ", extra=" + String.valueOf(extra));
        mState = State.Stopped;
        relaxResources(true);
        giveUpAudioFocus();

        return true; // true indicates we handled the error
    }

    public void onGainedAudioFocus() {
//        Log.v(TAG, "onGainedAudioFocus : enter");

//        Toast.makeText(getApplicationContext(), "gained audio focus.", Toast.LENGTH_SHORT).show();

        mAudioFocus = AudioFocus.Focused;

        // restart media player with new focus settings
        if (mState == State.Playing)
            configAndStartMediaPlayer();

        processStatusRequest();

//        Log.v(TAG, "onGainedAudioFocus : exit");
    }

    public void onLostAudioFocus(boolean canDuck) {
//        Log.v(TAG, "onLostAudioFocus : enter");

//        Toast.makeText(getApplicationContext(), "lost audio focus." + (canDuck ? "can duck" : "no duck"), Toast.LENGTH_SHORT).show();

        mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;

        // start/restart/pause media player with new focus settings
        if (mPlayer != null && mPlayer.isPlaying())
            configAndStartMediaPlayer();

        processStatusRequest();

//        Log.v(TAG, "onLostAudioFocus : exit");
    }

    @Override
    public void onDestroy() {
//        Log.i(TAG, "onDestroy : enter");

        // Service is being killed, so make sure we release our resources
        mState = State.Stopped;
        relaxResources(true);
        giveUpAudioFocus();

//        Log.i(TAG, "onDestroy : exit");
    }

    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {

        @Override
        public void run() {

            updateLastPlayed(mPlayer.getCurrentPosition());

            processStatusRequest();

            timerHandler.postDelayed(this, 1000);
        }
    };

    void updateLastPlayed( int currentPosition ) {

        if( mEpisodeHolder == null ) {
            return;
        }

        mEpisodeHolder.setEpisodeLastPlayed(currentPosition);

        ContentValues values = new ContentValues();
        values.put(EpisodeConstants.FIELD_PLAYED, 1 );
        values.put(EpisodeConstants.FIELD_LASTPLAYED, currentPosition );

        getContentResolver().update(ContentUris.withAppendedId( EpisodeConstants.CONTENT_URI, mEpisodeId ), values, null, null );

    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

}

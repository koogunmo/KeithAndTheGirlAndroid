package com.keithandthegirl.app.ui.episode;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.content.Loader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.keithandthegirl.app.R;
import com.keithandthegirl.app.db.model.EpisodeConstants;
import com.keithandthegirl.app.db.model.EpisodeInfoHolder;
import com.keithandthegirl.app.loader.AbstractAsyncTaskLoader;
import com.keithandthegirl.app.loader.WrappedLoaderCallbacks;
import com.keithandthegirl.app.loader.WrappedLoaderResult;
import com.keithandthegirl.app.services.media.MediaService;
import com.keithandthegirl.app.sync.EpisodeDetailsAsyncTask;
import com.keithandthegirl.app.ui.custom.ExpandedHeightGridView;
import com.keithandthegirl.app.ui.gallery.ImageGalleryInfoHolder;
import com.keithandthegirl.app.ui.settings.SettingsActivity;
import com.keithandthegirl.app.utils.StringUtils;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link EpisodeFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class EpisodeFragment extends Fragment implements WrappedLoaderCallbacks<EpisodeInfoHolder>, AdapterView.OnItemClickListener {
    private static final String TAG = EpisodeFragment.class.getSimpleName();

    private static final String ARG_EPISODE_ID = "ARG_EPISODE_ID";
    private static final int VIEW_EPISODE_DETAILS = 0;
    private static final int VIEW_PROGRESS = 1;

    ViewSwitcher mMainViewSwitcher;
    @InjectView(R.id.episodeHeaderBackgroundImageView)
    ImageView mEpisodeHeaderBackgroundImageView;

    @InjectView(R.id.episodeDateTextView)
    TextView mEpisodeDateTextView;

    @InjectView(R.id.episodeNumberTextView)
    TextView mEpisodeNumberTextView;

    @InjectView(R.id.episodeTitleTextView)
    TextView mEpisodeTitleTextView;

    @InjectView(R.id.episodeGuestsTextView)
    TextView mEpisodeGuestsTextView;

    @InjectView(R.id.episodeGuestImagesGridView)
    ExpandedHeightGridView mEpisodeGuestImagesGridView;

    @InjectView(R.id.episodeImagesGridView)
    ExpandedHeightGridView mEpisodeImagesGridView;

    @InjectView(R.id.episodeShowNotesWebView)
    WebView mEpisodeShowNotesWebView;

    @InjectView(R.id.episodeDetailsLayout)
    View mEpisodeDetailsLayout;

    @InjectView(R.id.guestNamesLayout)
    View mEpisodeGuestNamesLayout;

    @InjectView(R.id.episodeImagesLayout)
    View mEpisodeImagesLayout;

    @InjectView(R.id.guestImagesLayout)
    View mGuestImagesLayout;

    private long mEpisodeId;
    private EpisodeEventListener mEpisodeEventListener;
    private EpisodeInfoHolder mEpisodeInfoHolder;
    private List<String> mEpisodeGuestImagesList;
    private EpisodeGuestImageAdapter mEpisodeGuestImageAdapter;
    private List<ImageGalleryInfoHolder> mImageGalleryInfoList;
    private EpisodeImageAdapter mEpisodeImageAdapter;

    private MenuItem mPlayEpisodeMenuItem, mDownloadMenuItem, mDeleteMenuItem;
    private DownloadManager mDownloadManager;

    private EpisodeDetailsCompleteReceiver mEpisodeDetailsCompleteReceiver = new EpisodeDetailsCompleteReceiver();

    private boolean mDownloadMobile, mDownloadWifi, mMobileConnected, mWifiConnected;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param episodeId episode id for the current episode.
     * @return A new instance of fragment EpisodeFragment.
     */
    public static EpisodeFragment newInstance(long episodeId) {
        EpisodeFragment fragment = new EpisodeFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_EPISODE_ID, episodeId);
        fragment.setArguments(args);
        return fragment;
    }

    public EpisodeFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mEpisodeId = getArguments().getLong(ARG_EPISODE_ID);
        }

        mDownloadManager = (DownloadManager) getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        // only load the data once and set retain instance
        getLoaderManager().initLoader(1, null, this);
        setRetainInstance(true);

        mEpisodeGuestImagesList = new ArrayList<>();
        mEpisodeGuestImageAdapter = new EpisodeGuestImageAdapter(getActivity(), mEpisodeGuestImagesList);

        mImageGalleryInfoList = new ArrayList<>();
        mEpisodeImageAdapter = new EpisodeImageAdapter(getActivity(), mImageGalleryInfoList);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View fragmentView = inflater.inflate(R.layout.fragment_episode, container, false);
        ButterKnife.inject(this, fragmentView);

        mMainViewSwitcher = (ViewSwitcher) fragmentView;
        mMainViewSwitcher.setDisplayedChild(VIEW_PROGRESS);
        mEpisodeGuestImagesGridView.setAdapter(mEpisodeGuestImageAdapter);

        mEpisodeImagesGridView.setAdapter(mEpisodeImageAdapter);
        mEpisodeImagesGridView.setOnItemClickListener(this);

        return fragmentView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);

        // only load the data once and set retain instance
        getLoaderManager().initLoader(1, null, this);
//        setRetainInstance(true);

        // if this is a config change we already have episode info loaded so update UI.
        if (mEpisodeInfoHolder != null) {
            updateUI(mEpisodeInfoHolder);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        IntentFilter episodeDetailsCompleteIntentFilter = new IntentFilter( EpisodeDetailsAsyncTask.COMPLETE_ACTION );
        getActivity().registerReceiver( mEpisodeDetailsCompleteReceiver, episodeDetailsCompleteIntentFilter );

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
        mDownloadMobile = sharedPref.getBoolean( SettingsActivity.KEY_PREF_DOWNLOAD_MOBILE, false );
        mDownloadWifi = sharedPref.getBoolean( SettingsActivity.KEY_PREF_DOWNLOAD_WIFI, false );

        updateConnectedFlags();
    }

    @Override
    public void onPause() {
        super.onPause();

        if( null != mEpisodeDetailsCompleteReceiver ) {
            getActivity().unregisterReceiver( mEpisodeDetailsCompleteReceiver );
        }

    }

    @Override
    public void onAttach( final Activity activity ) {
        super.onAttach( activity );

        if( activity instanceof EpisodeEventListener ) {
            mEpisodeEventListener = (EpisodeEventListener) activity;
        } else {
            Log.w(TAG, "No one is registered for episode events!");
        }

    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.episode, menu);

        mPlayEpisodeMenuItem = menu.findItem( R.id.action_play_episode );
        mDownloadMenuItem = menu.findItem( R.id.action_download );
        mDeleteMenuItem = menu.findItem( R.id.action_delete );

        swapMenuItems();

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch( item.getItemId() ) {

            case R.id.action_play_episode :

                updateConnectedFlags();

                if( mWifiConnected || mMobileConnected ) {

                    Intent intent = new Intent(getActivity(), MediaService.class);
                    intent.setAction(MediaService.ACTION_URL);
                    intent.putExtra(MediaService.EXTRA_EPISODE_ID, mEpisodeId);
                    getActivity().startService(intent);

                } else {

                    AlertDialog.Builder builder = new AlertDialog.Builder( getActivity() );
                    builder.setTitle( R.string.network_connected_title )
                           .setMessage( R.string.network_connected_message );
                    builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            // User cancelled the dialog
                        }
                    });

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }

                break;

            case R.id.action_download :
                queueDownload();
                break;

            case R.id.action_delete :
                deleteEpisode();
                break;

        }

        return super.onOptionsItemSelected(item);
    }

    private void swapMenuItems() {
        if( null == mDownloadMenuItem || null == mDeleteMenuItem ) {
            return;
        }

        if( null == mEpisodeInfoHolder ) {
            mDownloadMenuItem.setVisible( false );
            mDownloadMenuItem.setEnabled( false );
            mDeleteMenuItem.setVisible( false );
            mDeleteMenuItem.setEnabled( false );

            return;
        }

        if( !mEpisodeInfoHolder.isEpisodePublic() ) {

            mPlayEpisodeMenuItem.setVisible( false );
            mPlayEpisodeMenuItem.setEnabled( false );

            if( mEpisodeInfoHolder.isEpisodeDownloaded() ) {
                mDownloadMenuItem.setVisible( false );
                mDownloadMenuItem.setEnabled( false );
                mDeleteMenuItem.setVisible( true );
                mDeleteMenuItem.setEnabled( true );
            } else {
                mDownloadMenuItem.setVisible( false );
                mDownloadMenuItem.setEnabled( false );
                mDeleteMenuItem.setVisible( false );
                mDeleteMenuItem.setEnabled( false );
            }

            return;
        } else {

            mPlayEpisodeMenuItem.setVisible( true );
            mPlayEpisodeMenuItem.setEnabled( true );

        }

        if( mEpisodeInfoHolder.isEpisodeDownloaded() ) {
            mDownloadMenuItem.setVisible( false );
            mDownloadMenuItem.setEnabled( false );
            mDeleteMenuItem.setVisible( true );
            mDeleteMenuItem.setEnabled( true );
        } else {
            if (mEpisodeInfoHolder.getEpisodeDownloadId() != -1) {
                mDownloadMenuItem.setVisible(false);
                mDownloadMenuItem.setEnabled(false);
                mDeleteMenuItem.setVisible(true);
                mDeleteMenuItem.setEnabled(true);
            } else {

                mDownloadMenuItem.setVisible(true);
                mDownloadMenuItem.setEnabled(true);
                mDeleteMenuItem.setVisible(false);
                mDeleteMenuItem.setEnabled(false);
            }
        }
    }

    private boolean isDownloading() {
        if( null == mEpisodeInfoHolder ) {
            return false;
        }

        boolean isDownloading = false;
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(
                DownloadManager.STATUS_PAUSED|
                        DownloadManager.STATUS_PENDING|
                        DownloadManager.STATUS_RUNNING);
        Cursor cur = mDownloadManager.query(query);
        int col = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
        for(cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {

            if(cur.getString(col).contains(mEpisodeInfoHolder.getEpisodeFilename())) {
                switch (cur.getInt(cur.getColumnIndex(DownloadManager.COLUMN_STATUS))) {
                    case DownloadManager.STATUS_FAILED:
                        isDownloading = false;
                        break;
                    case DownloadManager.STATUS_PAUSED:
                        isDownloading = false;
                        break;
                    case DownloadManager.STATUS_PENDING:
                        isDownloading = false;
                        break;
                    case DownloadManager.STATUS_RUNNING:
                        isDownloading = true;
                        break;
                    case DownloadManager.STATUS_SUCCESSFUL:
                        isDownloading = false;
                        break;
                }

            }
        }
        cur.close();

        return isDownloading;
    }

    private void queueDownload() {

        if( null == mEpisodeInfoHolder ) {
            return;
        }

        if( !isDownloading() ) {

            updateConnectedFlags();

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(mEpisodeInfoHolder.getEpisodeFileUrl()));

            // only download via Any Newtwork Connection
            if( mDownloadMobile && !mDownloadWifi ) {
                request.setAllowedNetworkTypes( DownloadManager.Request.NETWORK_MOBILE );
            }
            if( mDownloadWifi && !mDownloadMobile ) {
                request.setAllowedNetworkTypes( DownloadManager.Request.NETWORK_WIFI );
            }

            request.setTitle(mEpisodeInfoHolder.getShowPrefix() + ":" + mEpisodeInfoHolder.getEpisodeNumber());
            request.setDescription(mEpisodeInfoHolder.getEpisodeTitle());

            // show download status in notification bar
            request.setVisibleInDownloadsUi(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(getActivity(), null, mEpisodeInfoHolder.getEpisodeFilename());
            request.setMimeType( null );

            // enqueue this request
            long downloadId = mDownloadManager.enqueue(request);

            if (downloadId > 0) {

                mEpisodeInfoHolder.setEpisodeDownloadId(downloadId);
                mEpisodeInfoHolder.setEpisodeDownloaded(false);

                swapMenuItems();

                ContentValues values = new ContentValues();
                values.put(EpisodeConstants.FIELD_DOWNLOAD_ID, downloadId);
                values.put(EpisodeConstants.FIELD_DOWNLOADED, 0);
                getActivity().getContentResolver().update(ContentUris.withAppendedId(EpisodeConstants.CONTENT_URI, mEpisodeId), values, null, null);
            }
        }
    }

    private void deleteEpisode() {
        if( null == mEpisodeInfoHolder ) {
            return;
        }

        if( isDownloading() ) {
            mDownloadManager.remove(mEpisodeInfoHolder.getEpisodeDownloadId());
        }
//        else {
//
//            File externalFile = new File(getActivity().getExternalFilesDir(null), mEpisodeInfoHolder.getEpisodeFilename());
//            if (externalFile.exists()) {
//                boolean deleted = externalFile.delete();
//                if (deleted) {
//                    Log.i(TAG, "deleteEpisode : externalFile deleted!");
//
//                }
//            }
//        }

        mEpisodeInfoHolder.setEpisodeDownloadId(-1);
        mEpisodeInfoHolder.setEpisodeDownloaded(false);

        ContentValues values = new ContentValues();
        values.put(EpisodeConstants.FIELD_DOWNLOAD_ID, -1);
        values.put(EpisodeConstants.FIELD_DOWNLOADED, 0);
        getActivity().getContentResolver().update(ContentUris.withAppendedId(EpisodeConstants.CONTENT_URI, mEpisodeId), values, null, null);

        swapMenuItems();
    }

    private void updateUI(EpisodeInfoHolder episodeHolder) {
        if (episodeHolder == null) {
            return;
        } // early out if we haven't set data yet

        Picasso.with(getActivity()).load(episodeHolder.getShowCoverImageUrl()).into(mEpisodeHeaderBackgroundImageView);
        mEpisodeDateTextView.setText(episodeHolder.getEpisodePosted());
        mEpisodeNumberTextView.setText(String.valueOf(episodeHolder.getEpisodeNumber()));
        mEpisodeTitleTextView.setText(episodeHolder.getEpisodeTitle());

        if (StringUtils.isNullOrEmpty(episodeHolder.getGuestNames())) {
            mEpisodeGuestNamesLayout.setVisibility(View.GONE);
        } else {
            mEpisodeGuestNamesLayout.setVisibility(View.VISIBLE);
            mEpisodeGuestsTextView.setText(episodeHolder.getGuestNames());
        }

        if (episodeHolder.getEpisodeGuestImages().size() > 0) {
            mGuestImagesLayout.setVisibility(View.VISIBLE);
            mEpisodeGuestImagesList.clear();
            mEpisodeGuestImagesList.addAll(episodeHolder.getEpisodeGuestImages());
            mEpisodeGuestImageAdapter.notifyDataSetChanged();
        } else {
            mGuestImagesLayout.setVisibility(View.GONE);
        }

        if (episodeHolder.getEpisodeImages().size() > 0) {
            mEpisodeImagesLayout.setVisibility(View.VISIBLE);
            mImageGalleryInfoList.clear();
            mImageGalleryInfoList.addAll(episodeHolder.getEpisodeImages());
            mEpisodeImageAdapter.notifyDataSetChanged();
        } else {
            mEpisodeImagesLayout.setVisibility(View.GONE);
        }

        swapMenuItems();

        // We are turnig off the progress right away if no notes but after web load if we have them!
        if (StringUtils.isNullOrEmpty(episodeHolder.getEpisodeDetailNotes())) {
            mEpisodeDetailsLayout.setVisibility(View.GONE);
            mMainViewSwitcher.setDisplayedChild(VIEW_EPISODE_DETAILS);
        } else {
            mEpisodeDetailsLayout.setVisibility(View.VISIBLE);
            WebSettings settings = mEpisodeShowNotesWebView.getSettings();
            settings.setDefaultTextEncodingName("utf-8");
            String html = episodeHolder.getEpisodeDetailNotes();
            if (!StringUtils.isNullOrEmpty(html.trim())) {
                html = "<ul><li>" + html.replaceAll("\r\n", "</li><li>") + "</li></ul></br>";
            }
            mEpisodeShowNotesWebView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);

            mEpisodeShowNotesWebView.setBackgroundColor(0);
            mEpisodeShowNotesWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(final WebView view, final String url) {
                    super.onPageFinished(view, url);
                    mMainViewSwitcher.setDisplayedChild(VIEW_EPISODE_DETAILS);
                }
            });
        }
    }

    @Override
    public Loader<WrappedLoaderResult<EpisodeInfoHolder>> onCreateLoader(final int id, final Bundle args) {
        return new AbstractAsyncTaskLoader<EpisodeInfoHolder>(getActivity()) {
            @Override
            public EpisodeInfoHolder load() throws Exception {
                return EpisodeInfoHolder.loadEpisode( getActivity(), mEpisodeId );
            }
        };
    }

    @Override
    public void onLoadFinished(final Loader<WrappedLoaderResult<EpisodeInfoHolder>> loader, final WrappedLoaderResult<EpisodeInfoHolder> wrappedData) {
        if (wrappedData.hasException()) {
            Log.e(TAG, "failed getting episode info", wrappedData.getException());
            // TODO what to display when we failed getting stuff from DB?
        } else {
            mEpisodeInfoHolder = wrappedData.getWrappedData();

            updateUI(mEpisodeInfoHolder);

            if (mEpisodeEventListener != null) {
                mEpisodeEventListener.onEpisodeLoaded(mEpisodeInfoHolder);
            }

            if(StringUtils.isNullOrEmpty(mEpisodeInfoHolder.getEpisodeDetailNotes())) {
                scheduleWorkItem();
            }
        }
    }

    @Override
    public void onLoaderReset(final Loader<WrappedLoaderResult<EpisodeInfoHolder>> loader) { }

    @Override
    public void onItemClick(final AdapterView<?> parent, final View view, final int position, final long id) {
        if (mEpisodeEventListener != null) {
            mEpisodeEventListener.onEpisodeImageClicked(position, mEpisodeInfoHolder.getEpisodeImages());
        }
    }

    // Checks the network connection and sets the wifiConnected and mobileConnected
    // variables accordingly.
    private void updateConnectedFlags() {
        ConnectivityManager connMgr = (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();
        if( null != activeInfo && activeInfo.isConnected() ) {
            mWifiConnected = activeInfo.getType() == ConnectivityManager.TYPE_WIFI;
            mMobileConnected = activeInfo.getType() == ConnectivityManager.TYPE_MOBILE;
        } else {
            mWifiConnected = false;
            mMobileConnected = false;
        }
    }

    private void scheduleWorkItem() {
        if( !mWifiConnected && !mMobileConnected ) {
            return;
        }

        new EpisodeDetailsAsyncTask( getActivity(), (int) mEpisodeId ).execute();
    }

    private class EpisodeDetailsCompleteReceiver extends BroadcastReceiver {

        @Override
        public void onReceive( Context context, Intent intent ) {

            // when we receive a syc complete action reset the loader so it can refresh the content
            if( intent.getAction().equals( EpisodeDetailsAsyncTask.COMPLETE_ACTION ) ) {
                getLoaderManager().restartLoader( 1, null, EpisodeFragment.this );
            }
        }
    }

    private class EpisodeGuestImageAdapter extends ArrayAdapter<String> {
        public EpisodeGuestImageAdapter(final Context context, final List<String> objects) {
            super(context, -1, objects);
        }

        @Override
        public View getView(final int position, View convertView, final ViewGroup parent) {
            String guestImageUrl = getItem(position);

            if (convertView == null) {
                LayoutInflater layoutInflater = LayoutInflater.from(getContext());
                convertView = layoutInflater.inflate(R.layout.gridview_item_guest_image, parent, false);

                ViewHolder viewHolder = new ViewHolder();
                viewHolder.imageView = (ImageView) convertView.findViewById(R.id.image);
                convertView.setTag(viewHolder);
            }
            ViewHolder viewHolder = (ViewHolder) convertView.getTag();
            Picasso.with(getContext()).load(guestImageUrl).into(viewHolder.imageView);

            return convertView;
        }

        private class ViewHolder {
            ImageView imageView;
        }
    }

    private class EpisodeImageAdapter extends ArrayAdapter<ImageGalleryInfoHolder> {
        public EpisodeImageAdapter(final Context context, final List<ImageGalleryInfoHolder> objects) {
            super(context, -1, objects);
        }

        @Override
        public View getView(final int position, View convertView, final ViewGroup parent) {
            ImageGalleryInfoHolder imageHolder = getItem(position);

            if (convertView == null) {
                LayoutInflater layoutInflater = LayoutInflater.from(getContext());
                convertView = layoutInflater.inflate(R.layout.gridview_item_image, parent, false);
                ViewHolder viewHolder = new ViewHolder();
                viewHolder.imageView = (ImageView) convertView.findViewById(R.id.image);
                convertView.setTag(viewHolder);
            }
            ViewHolder viewHolder = (ViewHolder) convertView.getTag();
            if (imageHolder.isExplicit()) {
                Picasso.with(getContext()).load(R.drawable.img_explicit_warning).resize(150, 150).centerInside().into(viewHolder.imageView);
            } else {
                Picasso.with(getContext()).load(imageHolder.getImageUrl()).resize(150, 150).centerInside().into(viewHolder.imageView);
            }

            return convertView;
        }

        private class ViewHolder {
            ImageView imageView;
        }
    }

    public interface EpisodeEventListener {
        void onEpisodeLoaded(EpisodeInfoHolder episodeInfoHolder);
        void onEpisodeImageClicked(int position, ArrayList<ImageGalleryInfoHolder> imageHolders);
    }
}
package com.keithandthegirl.app.db.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Define a Service that returns an IBinder for the
 * sync adapter class, allowing the sync adapter framework to call
 * onPerformSync().
 *
 * Created by dmfrey on 3/10/14.
 */
public class SyncService extends Service {

    private static final String TAG = SyncService.class.getSimpleName();

    // Storage for an instance of the sync adapter
    private static SyncAdapter sSyncAdapter = null;

    // Object to use as a thread-safe lock
    private static final Object sSyncAdapterLock = new Object();

    /*
     * Instantiate the sync adapter object.
     */
    @Override
    public void onCreate() {
        Log.v( TAG, "onCreate : enter" );

        /*
         * Create the sync adapter as a singleton.
         * Set the sync adapter as syncable
         * Disallow parallel syncs
         */
        synchronized( sSyncAdapterLock ) {
            if( sSyncAdapter == null ) {
                sSyncAdapter = new SyncAdapter( getApplicationContext(), true );
            }
        }

        Log.v( TAG, "onCreate : exit" );
    }

    /**
     * Return an object that allows the system to invoke
     * the sync adapter.
     *
     */
    @Override
    public IBinder onBind( Intent intent ) {
        Log.v( TAG, "onBind : enter" );

        /*
         * Get the object that allows external processes
         * to call onPerformSync(). The object is created
         * in the base class code when the SyncAdapter
         * constructors call super()
         */

        Log.v( TAG, "onBind : exit" );
        return sSyncAdapter.getSyncAdapterBinder();
    }

}

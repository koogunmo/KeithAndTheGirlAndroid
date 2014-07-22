package com.keithandthegirl.app.account;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import com.keithandthegirl.app.ui.AuthenticatorActivity;

import static android.accounts.AccountManager.KEY_BOOLEAN_RESULT;
import static com.keithandthegirl.app.account.AccountGeneral.AUTHTOKEN_TYPE_FULL_ACCESS;
import static com.keithandthegirl.app.account.AccountGeneral.AUTHTOKEN_TYPE_FULL_ACCESS_LABEL;
import static com.keithandthegirl.app.account.AccountGeneral.AUTHTOKEN_TYPE_READ_ONLY;
import static com.keithandthegirl.app.account.AccountGeneral.AUTHTOKEN_TYPE_READ_ONLY_LABEL;
import static com.keithandthegirl.app.account.AccountGeneral.sServerAuthenticate;

/**
 * Implement AbstractAccountAuthenticator and stub out all
 * of its methods
 *
 * Created by dmfrey on 3/10/14.
 */
public class Authenticator extends AbstractAccountAuthenticator {
    private static final String TAG = Authenticator.class.getSimpleName();

    private Context mContext;

    // Simple constructor
    public Authenticator( Context context ) {
        super( context );
        mContext = context;
    }

    // Don't add additional accounts
    @Override
    public Bundle addAccount( AccountAuthenticatorResponse response, String accountType, String authTokenType, String[] requiredFeatures, Bundle options ) throws NetworkErrorException {
        Log.i( TAG, "addAccount : enter" );

        final Intent intent = new Intent( mContext, AuthenticatorActivity.class );
        intent.putExtra( AuthenticatorActivity.ARG_ACCOUNT_TYPE, accountType );
        intent.putExtra( AuthenticatorActivity.ARG_AUTH_TYPE, authTokenType );
        intent.putExtra( AuthenticatorActivity.ARG_IS_ADDING_NEW_ACCOUNT, true );
        intent.putExtra( AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response );

        final Bundle bundle = new Bundle();
        bundle.putParcelable( AccountManager.KEY_INTENT, intent );

        Log.i( TAG, "addAccount : exit" );
        return bundle;
    }

    @Override
    public Bundle getAuthToken( AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options ) throws NetworkErrorException {
        Log.i( TAG, "getAuthToken : enter" );

        // If the caller requested an authToken type we don't support, then
        // return an error
        if( !authTokenType.equals( AccountGeneral.AUTHTOKEN_TYPE_READ_ONLY ) && !authTokenType.equals( AccountGeneral.AUTHTOKEN_TYPE_FULL_ACCESS ) ) {

            final Bundle result = new Bundle();
            result.putString( AccountManager.KEY_ERROR_MESSAGE, "invalid authTokenType" );

            Log.i( TAG, "getAuthToken : exit" );
            return result;
        }

        // Extract the username and password from the Account Manager, and ask
        // the server for an appropriate AuthToken.
        final AccountManager am = AccountManager.get( mContext );

        String authToken = am.peekAuthToken( account, authTokenType );
        Log.d( TAG, "getAuthToken : authToken=" + authToken );

        // Lets give another try to authenticate the user
        if( TextUtils.isEmpty( authToken ) ) {

            final String password = am.getPassword( account );
            if( null != password ) {

                try {

                    Log.d( TAG, "getAuthToken : try to authenticate again" );

                    authToken = sServerAuthenticate.userSignIn( account.name, password, authTokenType );

                } catch( Exception e ) {
                    Log.e( TAG, "getAuthToken : error", e );
                }
            }

        }

        // If we get an authToken - we return it
        if( !TextUtils.isEmpty( authToken ) ) {
            Log.i( TAG, "getAuthToken : found authToken" );

            final Bundle result = new Bundle();
            result.putString( AccountManager.KEY_ACCOUNT_NAME, account.name );
            result.putString( AccountManager.KEY_ACCOUNT_TYPE, account.type );
            result.putString( AccountManager.KEY_AUTHTOKEN, authToken );

            return result;
        }

        // If we get here, then we couldn't access the user's password - so we
        // need to re-prompt them for their credentials. We do that by creating
        // an intent to display our AuthenticatorActivity.
        final Intent intent = new Intent( mContext, AuthenticatorActivity.class );
        intent.putExtra( AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response );
        intent.putExtra( AuthenticatorActivity.ARG_ACCOUNT_TYPE, account.type );
        intent.putExtra( AuthenticatorActivity.ARG_AUTH_TYPE, authTokenType );
        intent.putExtra( AuthenticatorActivity.ARG_ACCOUNT_NAME, account.name );

        final Bundle bundle = new Bundle();
        bundle.putParcelable( AccountManager.KEY_INTENT, intent );

        Log.i( TAG, "getAuthToken : exit" );
        return bundle;
    }

    @Override
    public String getAuthTokenLabel( String authTokenType ) {
        Log.i( TAG, "" +
                "" +
                "getAuthTokenLabel : enter" );

        if( AUTHTOKEN_TYPE_FULL_ACCESS.equals( authTokenType ) ) {

            Log.i( TAG, "getAuthTokenLabel : exit, full access" );
            return AUTHTOKEN_TYPE_FULL_ACCESS_LABEL;
        } else if( AUTHTOKEN_TYPE_READ_ONLY.equals( authTokenType ) ) {

            Log.i( TAG, "getAuthTokenLabel : exit, read only" );
            return AUTHTOKEN_TYPE_READ_ONLY_LABEL;
        } else {

            Log.i( TAG, "getAuthTokenLabel : exit" );
            return authTokenType + " (Label)";
        }

    }

    @Override
    public Bundle hasFeatures( AccountAuthenticatorResponse response, Account account, String[] features ) throws NetworkErrorException {
        Log.i( TAG, "hasFeatures : enter" );

        final Bundle result = new Bundle();
        result.putBoolean( KEY_BOOLEAN_RESULT, false );

        Log.i( TAG, "hasFeatures : exit" );
        return result;
    }

    @Override
    public Bundle getAccountRemovalAllowed( AccountAuthenticatorResponse response, Account account ) throws NetworkErrorException {
        Log.i( TAG, "getAccountRemovalAllowed : enter" );

        Bundle result = new Bundle();
        boolean allowed = true; // or whatever logic you want here
        result.putBoolean( AccountManager.KEY_BOOLEAN_RESULT, allowed );

        Log.i( TAG, "getAccountRemovalAllowed : exit" );
        return result;
    }

    @Override
    public Bundle editProperties( AccountAuthenticatorResponse response, String accountType ) {
        return null;
    }

    @Override
    public Bundle confirmCredentials( AccountAuthenticatorResponse response, Account account, Bundle options ) throws NetworkErrorException {
        return null;
    }

    @Override
    public Bundle updateCredentials( AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options ) throws NetworkErrorException {
        return null;
    }

}

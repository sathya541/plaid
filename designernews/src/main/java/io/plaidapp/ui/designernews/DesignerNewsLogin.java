/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.ui.designernews;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TextInputLayout;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.TransitionManager;
import android.util.Log;
import android.util.Patterns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.plaidapp.BuildConfig;
import io.plaidapp.base.designernews.data.api.model.AccessToken;
import io.plaidapp.designernews.R;
import io.plaidapp.base.designernews.data.api.DesignerNewsService;
import io.plaidapp.base.designernews.data.api.model.User;
import io.plaidapp.base.designernews.DesignerNewsPrefs;
import io.plaidapp.ui.transitions.FabTransform;
import io.plaidapp.ui.transitions.MorphTransform;
import io.plaidapp.util.ScrimUtil;
import io.plaidapp.base.util.glide.GlideApp;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DesignerNewsLogin extends Activity {

    private static final int PERMISSIONS_REQUEST_GET_ACCOUNTS = 0;

    private boolean isDismissing = false;
    private ViewGroup container;
    private TextView title;
    private TextInputLayout usernameLabel;
    private AutoCompleteTextView username;
    private CheckBox permissionPrimer;
    private TextInputLayout passwordLabel;
    private EditText password;
    private FrameLayout actionsContainer;
    private Button signup;
    private Button login;
    private ProgressBar loading;

    private DesignerNewsPrefs designerNewsPrefs;
    private boolean shouldPromptForPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_designer_news_login);
        bindViews();
        if (!FabTransform.setup(this, container)) {
            MorphTransform.setup(this, container,
                    ContextCompat.getColor(this, io.plaidapp.R.color.background_light),
                    getResources().getDimensionPixelSize(io.plaidapp.R.dimen.dialog_corners));
        }

        loading.setVisibility(View.GONE);
        setupAccountAutocomplete();
        username.addTextChangedListener(loginFieldWatcher);
        // the primer checkbox messes with focus order so force it
        username.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                password.requestFocus();
                return true;
            }
            return false;
        });
        password.addTextChangedListener(loginFieldWatcher);
        password.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && isLoginValid()) {
                login.performClick();
                return true;
            }
            return false;
        });
        designerNewsPrefs = DesignerNewsPrefs.get(this);
    }

    private void bindViews() {
        container = findViewById(R.id.container);
        title = findViewById(R.id.dialog_title);
        usernameLabel = findViewById(R.id.username_float_label);
        username = findViewById(R.id.username);
        permissionPrimer = findViewById(R.id.permission_primer);
        passwordLabel = findViewById(R.id.password_float_label);
        password = findViewById(R.id.password);
        actionsContainer = findViewById(R.id.actions_container);
        signup = findViewById(R.id.signup);
        login = findViewById(R.id.login);
        loading = findViewById(io.plaidapp.R.id.loading);
    }

    @Override
    @SuppressLint("NewApi")
    public void onEnterAnimationComplete() {
        /* Postpone some of the setup steps so that we can run it after the enter transition (if
        there is one). Otherwise we may show the permissions dialog or account dropdown during the
        enter animation which is jarring. */
        if (shouldPromptForPermission) {
            requestPermissions(new String[]{Manifest.permission.GET_ACCOUNTS},
                    PERMISSIONS_REQUEST_GET_ACCOUNTS);
            shouldPromptForPermission = false;
        }
        username.setOnFocusChangeListener((v, hasFocus) -> maybeShowAccounts());
        maybeShowAccounts();
    }

    @Override
    public void onBackPressed() {
        dismiss(null);
    }

    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_GET_ACCOUNTS) {
            TransitionManager.beginDelayedTransition(container);
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupAccountAutocomplete();
                username.requestFocus();
                username.showDropDown();
            } else {
                // if permission was denied check if we should ask again in the future (i.e. they
                // did not check 'never ask again')
                if (shouldShowRequestPermissionRationale(Manifest.permission.GET_ACCOUNTS)) {
                    setupPermissionPrimer();
                } else {
                    // denied & shouldn't ask again. deal with it (•_•) ( •_•)>⌐■-■ (⌐■_■)
                    TransitionManager.beginDelayedTransition(container);
                    permissionPrimer.setVisibility(View.GONE);
                }
            }
        }
    }

    public void doLogin(View view) {
        showLoading();
        getAccessToken(designerNewsPrefs.getApi(),
                username.getText().toString(),
                password.getText().toString());
    }

    public void signup(View view) {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.designernews.co/users/new")));
    }

    public void dismiss(View view) {
        isDismissing = true;
        setResult(Activity.RESULT_CANCELED);
        finishAfterTransition();
    }

    private void maybeShowAccounts() {
        if (username.hasFocus()
                && username.isAttachedToWindow()
                && username.getAdapter() != null
                && username.getAdapter().getCount() > 0) {
            username.showDropDown();
        }
    }

    boolean isLoginValid() {
        return username.length() > 0 && password.length() > 0;
    }

    @SuppressLint("InflateParams")
    private void showLoggedInUser(DesignerNewsService service) {
        final Call<User> authedUser = service.getAuthedUser();
        authedUser.enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (!response.isSuccessful()) return;
                final User user = response.body();
                designerNewsPrefs.setLoggedInUser(user);
                updateUiWithUser(user);
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(getClass().getCanonicalName(), t.getMessage(), t);
            }
        });
    }

    private void updateUiWithUser(User user) {
        final Toast confirmLogin = new Toast(getApplicationContext());
        final View v = LayoutInflater.from(DesignerNewsLogin.this)
                .inflate(io.plaidapp.R.layout.toast_logged_in_confirmation, null, false);
        ((TextView) v.findViewById(io.plaidapp.R.id.name)).setText(user.display_name.toLowerCase());
        // need to use app context here as the activity will be destroyed shortly
        GlideApp.with(getApplicationContext())
                .load(user.portrait_url)
                .placeholder(io.plaidapp.R.drawable.avatar_placeholder)
                .circleCrop()
                .transition(withCrossFade())
                .into((ImageView) v.findViewById(io.plaidapp.R.id.avatar));
        v.findViewById(io.plaidapp.R.id.scrim).setBackground(ScrimUtil
                .makeCubicGradientScrimDrawable(
                        ContextCompat.getColor(DesignerNewsLogin.this,
                                io.plaidapp.R.color.scrim),
                        5, Gravity.BOTTOM));
        confirmLogin.setView(v);
        confirmLogin.setGravity(Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0);
        confirmLogin.setDuration(Toast.LENGTH_LONG);
        confirmLogin.show();
    }

    void showLoginFailed() {
        Snackbar.make(container, io.plaidapp.R.string.login_failed, Snackbar.LENGTH_SHORT).show();
        showLogin();
        password.requestFocus();
    }

    private TextWatcher loginFieldWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            login.setEnabled(isLoginValid());
        }
    };

    private void showLoading() {
        TransitionManager.beginDelayedTransition(container);
        title.setVisibility(View.GONE);
        usernameLabel.setVisibility(View.GONE);
        permissionPrimer.setVisibility(View.GONE);
        passwordLabel.setVisibility(View.GONE);
        actionsContainer.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
    }

    private void showLogin() {
        TransitionManager.beginDelayedTransition(container);
        title.setVisibility(View.VISIBLE);
        usernameLabel.setVisibility(View.VISIBLE);
        passwordLabel.setVisibility(View.VISIBLE);
        actionsContainer.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
    }

    private void getAccessToken(DesignerNewsService service, String username, String password) {
        final Call<AccessToken> login = service.login(buildLoginParams(username, password));
        login.enqueue(new Callback<AccessToken>() {
            @Override
            public void onResponse(Call<AccessToken> call, Response<AccessToken> response) {
                if (response.isSuccessful()) {
                    designerNewsPrefs.setAccessToken(DesignerNewsLogin.this,
                            response.body().access_token);
                    showLoggedInUser(service);
                    setResult(Activity.RESULT_OK);
                    finish();
                } else {
                    showLoginFailed();
                }
            }

            @Override
            public void onFailure(Call<AccessToken> call, Throwable t) {
                Log.e(getClass().getCanonicalName(), t.getMessage(), t);
                showLoginFailed();
            }
        });
    }

    private Map<String, String> buildLoginParams(@NonNull String username,
            @NonNull String password) {
        final Map<String, String> loginParams = new HashMap<>(5);
        loginParams.put("client_id", BuildConfig.DESIGNER_NEWS_CLIENT_ID);
        loginParams.put("client_secret", BuildConfig.DESIGNER_NEWS_CLIENT_SECRET);
        loginParams.put("grant_type", "password");
        loginParams.put("username", username);
        loginParams.put("password", password);
        return loginParams;
    }

    @SuppressLint("NewApi")
    private void setupAccountAutocomplete() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) ==
                PackageManager.PERMISSION_GRANTED) {
            permissionPrimer.setVisibility(View.GONE);
            final Account[] accounts = AccountManager.get(this).getAccounts();
            final Set<String> emailSet = new HashSet<>();
            for (Account account : accounts) {
                if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                    emailSet.add(account.name);
                }
            }
            username.setAdapter(new ArrayAdapter<>(this,
                    R.layout.account_dropdown_item, new ArrayList<>(emailSet)));
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.GET_ACCOUNTS)) {
                setupPermissionPrimer();
            } else {
                permissionPrimer.setVisibility(View.GONE);
                shouldPromptForPermission = true;
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void setupPermissionPrimer() {
        permissionPrimer.setChecked(false);
        permissionPrimer.setVisibility(View.VISIBLE);
        permissionPrimer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                requestPermissions(new String[]{Manifest.permission.GET_ACCOUNTS},
                        PERMISSIONS_REQUEST_GET_ACCOUNTS);
            }
        });
    }
}

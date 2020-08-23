/*
 * Copyright (C) 2016 Jorge Ruesga
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
package com.ruesga.rview;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.slidingpanelayout.widget.SlidingPaneLayout.PanelSlideListener;

import com.google.android.material.snackbar.Snackbar;
import com.mikepenz.aboutlibraries.LibsBuilder;
import com.ruesga.rview.analytics.AnalyticsManagerImpl;
import com.ruesga.rview.databinding.ContentBinding;
import com.ruesga.rview.databinding.NavigationHeaderBinding;
import com.ruesga.rview.drawer.DrawerNavigationMenu;
import com.ruesga.rview.drawer.DrawerNavigationSubMenu;
import com.ruesga.rview.drawer.DrawerNavigationView;
import com.ruesga.rview.fragments.ChangeListByFilterFragment;
import com.ruesga.rview.fragments.DashboardFragment;
import com.ruesga.rview.fragments.FollowingChangeListFragment;
import com.ruesga.rview.fragments.PageableFragment;
import com.ruesga.rview.fragments.ServerInfoDialogFragment;
import com.ruesga.rview.fragments.SetAccountStatusDialogFragment;
import com.ruesga.rview.fragments.StatsFragment;
import com.ruesga.rview.fragments.TrendingChangeListFragment;
import com.ruesga.rview.gerrit.GerritApi;
import com.ruesga.rview.gerrit.filter.ChangeQuery;
import com.ruesga.rview.gerrit.model.ChangeInfo;
import com.ruesga.rview.gerrit.model.Features;
import com.ruesga.rview.misc.ActivityHelper;
import com.ruesga.rview.misc.CacheHelper;
import com.ruesga.rview.misc.EmojiHelper;
import com.ruesga.rview.misc.Formatter;
import com.ruesga.rview.misc.ModelHelper;
import com.ruesga.rview.misc.NotificationsHelper;
import com.ruesga.rview.misc.RviewImageHelper;
import com.ruesga.rview.misc.SerializationManager;
import com.ruesga.rview.misc.UriHelper;
import com.ruesga.rview.model.Account;
import com.ruesga.rview.model.CustomFilter;
import com.ruesga.rview.preferences.Constants;
import com.ruesga.rview.preferences.Preferences;
import com.ruesga.rview.providers.NotificationEntity;
import com.ruesga.rview.services.AccountStatusFetcherService;
import com.ruesga.rview.wizards.AuthorizationAccountSetupActivity;
import com.ruesga.rview.wizards.SetupAccountActivity;

import java.util.List;


import static com.ruesga.rview.preferences.Constants.MY_FILTERS_GROUP_BASE_ID;
import static com.ruesga.rview.preferences.Constants.OTHER_ACCOUNTS_GROUP_BASE_ID;

public class MainActivity extends ChangeListBaseActivity implements Reloadable {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_WIZARD = 1;
    private static final int REQUEST_ACCOUNT_SETTINGS = 2;

    private static final int MESSAGE_NAVIGATE_TO = 0;
    private static final int MESSAGE_DELETE_ACCOUNT = 1;

    @Keep
    public static class Model implements Parcelable {
        public String accountName;
        public String accountRepository;
        public boolean isAccountExpanded;

        int currentNavigationItemId = INVALID_ITEM;
        String filterName;
        String filterQuery;
        int selectedChangeId = INVALID_ITEM;

        public Model() {
        }

        protected Model(Parcel in) {
            if (in.readInt() == 1) {
                accountName = in.readString();
            }
            if (in.readInt() == 1) {
                accountRepository = in.readString();
            }
            isAccountExpanded = in.readByte() != 0;
            currentNavigationItemId = in.readInt();
            if (in.readInt() == 1) {
                filterName = in.readString();
            }
            if (in.readInt() == 1) {
                filterQuery = in.readString();
            }
            selectedChangeId = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(!TextUtils.isEmpty(accountName) ? 1 : 0);
            if (!TextUtils.isEmpty(accountName)) {
                dest.writeString(accountName);
            }
            dest.writeInt(!TextUtils.isEmpty(accountRepository) ? 1 : 0);
            if (!TextUtils.isEmpty(accountRepository)) {
                dest.writeString(accountRepository);
            }
            dest.writeByte((byte) (isAccountExpanded ? 1 : 0));
            dest.writeInt(currentNavigationItemId);
            dest.writeInt(!TextUtils.isEmpty(filterName) ? 1 : 0);
            if (!TextUtils.isEmpty(filterName)) {
                dest.writeString(filterName);
            }
            dest.writeInt(!TextUtils.isEmpty(filterQuery) ? 1 : 0);
            if (!TextUtils.isEmpty(filterQuery)) {
                dest.writeString(filterQuery);
            }
            dest.writeInt(selectedChangeId);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Model> CREATOR = new Creator<Model>() {
            @Override
            public Model createFromParcel(Parcel in) {
                return new Model(in);
            }

            @Override
            public Model[] newArray(int size) {
                return new Model[size];
            }
        };
    }

    @Keep
    @SuppressWarnings({"UnusedParameters", "unused"})
    public static class EventHandlers {
        private final MainActivity mActivity;

        public EventHandlers(MainActivity activity) {
            mActivity = activity;
        }

        public void onSwitcherPressed(View v) {
            mActivity.performShowAccount(!mActivity.mModel.isAccountExpanded);
        }
    }

    private final Handler.Callback mMessenger = message -> {
        // Has the activity saved its state? Ignore any operation from here then.
        if (hasStateSaved()) {
            return true;
        }

        if (message.what == MESSAGE_NAVIGATE_TO) {
            performNavigateTo();
            return true;
        }
        if (message.what == MESSAGE_DELETE_ACCOUNT) {
            performDeleteAccount((String) message.obj);
        }
        return false;
    };

    private final BroadcastReceiver mAccountStatusChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mAccount != null && intent != null) {
                String account = intent.getStringExtra(AccountStatusFetcherService.EXTRA_ACCOUNT);
                if (mAccount.getAccountHash().equals(account)) {
                    mAccount = Preferences.getAccount(context);
                    setAnalyticsAccount(mAccount);
                    updateAccountStatus();
                    performUpdateNavigationDrawer(mModel.isAccountExpanded);
                }
            }
        }
    };

    private ContentBinding mBinding;
    private NavigationHeaderBinding mHeaderDrawerBinding;

    private Model mModel;
    private final EventHandlers mEventHandlers = new EventHandlers(this);

    private Account mAccount;
    private List<Account> mAccounts;
    private List<CustomFilter> mCustomFilters;

    private Handler mUiHandler;

    private boolean mIsTwoPane;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        mUiHandler = new Handler(mMessenger);
        mIsTwoPane = getResources().getBoolean(R.bool.config_is_two_pane);

        super.onCreate(savedInstanceState);

        if (getIntent() != null) {
            // Set the account if requested and dismiss notifications for this account
            String accountId = getIntent().getStringExtra(Constants.EXTRA_ACCOUNT_HASH);
            if (!TextUtils.isEmpty(accountId)) {
                Preferences.setAccount(this, ModelHelper.getAccountFromHash(this, accountId));
                NotificationEntity.markAccountNotificationsAsRead(this, accountId);
                NotificationEntity.dismissAccountNotifications(this, accountId);
            }
        }

        mBinding = DataBindingUtil.setContentView(this, R.layout.content);

        if (savedInstanceState != null) {
            mModel = savedInstanceState.getParcelable(getClass().getSimpleName() + "_model");
        }
        if (mModel == null) {
            mModel = new Model();
        }

        setupActivity();
        loadAccounts();
        if (savedInstanceState == null) {
            launchAddAccountIfNeeded();
        }
        setupNavigationDrawer();
        updateAccountCustomFilters();

        IntentFilter filter = new IntentFilter();
        filter.addAction(AccountStatusFetcherService.ACCOUNT_STATUS_FETCHER_ACTION);
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mAccountStatusChangedReceiver, filter);
        if (mAccount != null) {
            fetchAccountStatus(mAccount);
            AnalyticsManagerImpl.instance().accountSelected(this, mAccount);
        }

        mHeaderDrawerBinding.setModel(mModel);
        mHeaderDrawerBinding.setHandlers(mEventHandlers);

        if (getSupportActionBar() != null) {
            if (mModel.filterName != null) {
                getSupportActionBar().setTitle(mModel.filterName);
                if (mModel.filterQuery != null) {
                    getSupportActionBar().setSubtitle(mModel.filterQuery);
                }
            }
        }

        int defaultMenu = Preferences.getAccountHomePageId(this, mAccount);
        if (savedInstanceState != null) {
            Fragment detailsFragment = getSupportFragmentManager().getFragment(
                    savedInstanceState, FRAGMENT_TAG_DETAILS);
            Fragment listFragment = getSupportFragmentManager().getFragment(
                    savedInstanceState, FRAGMENT_TAG_LIST);
            if (listFragment != null) {
                FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                        .setReorderingAllowed(false);
                tx.replace(R.id.content, listFragment, FRAGMENT_TAG_LIST);
                if (detailsFragment != null) {
                    tx.replace(R.id.details, detailsFragment, FRAGMENT_TAG_DETAILS);
                }
                tx.commit();

            } else {
                // Navigate to current item
                requestNavigateTo(mModel.currentNavigationItemId == INVALID_ITEM
                        ? defaultMenu : mModel.currentNavigationItemId, true, false);
            }
        } else {
            // Navigate to current item
            requestNavigateTo(mModel.currentNavigationItemId == INVALID_ITEM
                    ? defaultMenu : mModel.currentNavigationItemId, true, false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister services
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mAccountStatusChangedReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();

        updateAccountCustomFilters();
    }

    @Override
    public int getSelectedChangeId() {
        return mModel.selectedChangeId;
    }

    @Override
    public void onPause() {
        super.onPause();
        mUiHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(getClass().getSimpleName() + "_model", mModel);

        //Save the fragment's instance
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_LIST);
        if (fragment != null) {
            getSupportFragmentManager().putFragment(outState, FRAGMENT_TAG_LIST, fragment);
        }
        fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DETAILS);
        if (fragment != null) {
            getSupportFragmentManager().putFragment(outState, FRAGMENT_TAG_DETAILS, fragment);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WIZARD) {
            if (resultCode == RESULT_OK) {
                // Save the account
                Account newAccount = data.getParcelableExtra(SetupAccountActivity.EXTRA_ACCOUNT);
                boolean accountExists = false;
                for (Account account : mAccounts) {
                    // Current account
                    if (account.isSameAs(newAccount)) {
                        accountExists = true;
                        break;
                    }
                }

                if (!accountExists) {
                    mAccount = newAccount;
                    setAnalyticsAccount(mAccount);
                    mAccounts = Preferences.addOrUpdateAccount(this, mAccount);
                    Preferences.setAccount(this, mAccount);
                    Formatter.refreshCachedPreferences(this);
                    CacheHelper.createAccountCacheDir(this, mAccount);
                    setupAccountUrlHandlingStatus(mAccount, true);
                    NotificationsHelper.createNotificationChannel(this, mAccount);
                    AnalyticsManagerImpl.instance().accountEvent(this, mAccount, true);
                } else {
                    showWarning(R.string.account_exists);
                }

                // Switch to the new account
                performAccountSwitch();
            } else {
                // If we don't have account, then close the activity.
                // Otherwise, do nothing
                if (Preferences.getAccount(this) == null) {
                    finish();
                }
            }
        } else if (requestCode == REQUEST_ACCOUNT_SETTINGS) {
            // Refresh current view
            mAccount = Preferences.getAccount(this);
            setAnalyticsAccount(mAccount);
            if (mModel.currentNavigationItemId == INVALID_ITEM) {
                mModel.currentNavigationItemId = Preferences.getAccountHomePageId(this, mAccount);
            }
            Formatter.refreshCachedPreferences(this);
            performNavigateTo();
        }
    }

    @Override
    public ContentBinding getContentBinding() {
        return mBinding;
    }

    @Override
    public DrawerLayout getDrawerLayout() {
        return mBinding.drawerLayout;
    }

    private void loadAccounts() {
        mAccount = Preferences.getAccount(this);
        setAnalyticsAccount(mAccount);
        mAccounts = Preferences.getAccounts(this);
        if (mAccount != null) {
            Formatter.refreshCachedPreferences(this);
        }
    }

    private void setupNavigationDrawer() {
        // Bind the header, in code rather than in layout so we can have access to
        // binding variables
        mHeaderDrawerBinding = DataBindingUtil.inflate(LayoutInflater.from(this),
                R.layout.navigation_header, mBinding.drawerNavigationView, false);
        mBinding.drawerNavigationView.addHeaderView(mHeaderDrawerBinding.getRoot());
        mBinding.drawerNavigationView.setOnMenuButtonClickListener(this::onNavigationMenuItemClick);

        // Listen for click events and select the current one
        mBinding.drawerNavigationView.setDrawerNavigationItemSelectedListener(item -> {
            requestNavigateTo(item.getItemId(), false, true);
            return true;
        });

        // Update the accounts and current account information
        if (mAccount != null) {
            updateCurrentAccountDrawerInfo();
            updateAccountCustomFilters();
            updateAccountsDrawerInfo();
        }
        internalPerformShowAccount(mModel.isAccountExpanded);

        if (getMiniDrawerLayout() != null) {
            mBinding.drawerNavigationView.addMiniDrawerListener(new PanelSlideListener() {
                @Override
                public void onPanelSlide(@NonNull View panel, float slideOffset) {
                    // Ignore
                }

                @Override
                public void onPanelOpened(@NonNull View panel) {
                    // Ignore
                }

                @Override
                public void onPanelClosed(@NonNull View panel) {
                    internalPerformShowAccount(false);
                }
            });
        }
    }

    private void performShowAccount(boolean show) {
        if (getMiniDrawerLayout() != null && !getMiniDrawerLayout().isOpen()) {
            getMiniDrawerLayout().openPane();
            return;
        }

        internalPerformShowAccount(show);
    }

    private void internalPerformShowAccount(boolean show) {
        performUpdateNavigationDrawer(show);

        // Sync the current selection status
        if (mModel.currentNavigationItemId != -1) {
            performSelectItem(mModel.currentNavigationItemId, true);
        }
    }

    private void performUpdateNavigationDrawer(boolean show) {
        final boolean auth = mAccount != null && mAccount.hasAuthenticatedAccessMode();
        final boolean supportNotifications = mAccount != null && mAccount.hasNotificationsSupport()
                && Preferences.isAccountNotificationsEnabled(this, mAccount)
                && getResources().getBoolean(R.bool.has_notifications_support);
        final Menu menu = mBinding.drawerNavigationView.getMenu();
        menu.setGroupVisible(R.id.category_all, !show);
        menu.setGroupVisible(R.id.category_other_changes, !show);
        menu.setGroupVisible(R.id.category_my_menu, !show && auth);
        // Drafts are removed from Api on 2.15+
        menu.findItem(R.id.menu_drafts).setVisible(!show && auth
                && !ModelHelper.isEqualsOrGreaterVersionThan(mAccount, 2.15d));
        menu.setGroupVisible(R.id.category_my_filters,
                !show && mCustomFilters != null && !mCustomFilters.isEmpty());
        menu.setGroupVisible(R.id.category_my_account, show);
        menu.setGroupVisible(R.id.category_other_accounts, show);
        menu.setGroupVisible(R.id.category_info, show);
        menu.findItem(R.id.menu_account_stats).setVisible(show && auth);
        menu.findItem(R.id.menu_account_notifications).setVisible(show && supportNotifications);
        if (auth && mAccount.getServerVersion() != null) {
            GerritApi api = ModelHelper.getGerritApi(this, mAccount);
            boolean hasSupportAccountStatus = api.supportsFeature(
                    Features.ACCOUNT_STATUS, mAccount.getServerVersion());
            menu.findItem(R.id.menu_account_status).setVisible(show && hasSupportAccountStatus);
            updateAccountStatus();
        } else {
            menu.findItem(R.id.menu_account_status).setVisible(false);
        }

        mModel.isAccountExpanded = show;
        mHeaderDrawerBinding.setModel(mModel);
    }

    private boolean performSelectItem(int itemId, boolean force) {
        final Menu menu = mBinding.drawerNavigationView.getMenu();
        final MenuItem item = menu.findItem(itemId);
        if (item != null) {
            if (item.isCheckable()) {
                boolean changed = itemId != mModel.currentNavigationItemId;
                mBinding.drawerNavigationView.setCheckedItem(item.getItemId());
                mModel.currentNavigationItemId = itemId;
                return force || changed;
            } else {
                performNavigationAction(item);
            }
        }
        return false;
    }

    private void requestNavigateTo(int itemId, boolean force, boolean async) {
        // Select the item
        final boolean navigate = performSelectItem(itemId, force);

        // Close the drawer
        if (mBinding.drawerLayout != null) {
            if (mBinding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                mBinding.drawerLayout.closeDrawer(GravityCompat.START, true);
                mBinding.drawerNavigationView.updateNavigationView();
            }
        }

        if (navigate) {
            if (async) {
                Message.obtain(mUiHandler, MESSAGE_NAVIGATE_TO).sendToTarget();
            } else {
                performNavigateTo();
            }
        }
    }

    private void performNavigationAction(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_account_settings:
                openAccountSettings();
                break;
            case R.id.menu_account_notifications:
                openAccountNotifications();
                break;
            case R.id.menu_account_status:
                openAccountStatusChooser();
                break;
            case R.id.menu_account_stats:
                openAccountStats();
                break;
            case R.id.menu_account_server_info:
                openAccountServerInfo();
                break;
            case R.id.menu_account_password:
                openAccountSetup();
                break;
            case R.id.menu_drop_account:
                requestAccountDeletion(mAccount);
                break;
            case R.id.menu_add_account:
                Intent i = new Intent(this, SetupAccountActivity.class);
                startActivityForResult(i, REQUEST_WIZARD);
                break;
            case R.id.menu_share:
                String action = getString(R.string.action_share);
                String title = getString(R.string.share_app_title);
                final String deepLink = getString(R.string.link_play_store, getPackageName());
                String text = getString(R.string.share_app_text, deepLink);
                ActivityHelper.share(this, action, title, text);
                break;
            case R.id.menu_privacy:
                final String link = getString(R.string.link_privacy);
                ActivityHelper.openUriInCustomTabs(this, link);
                break;
            case R.id.menu_source_code:
                final String repository = getString(R.string.link_source_code);
                ActivityHelper.openUriInCustomTabs(this, repository);
                break;
            case R.id.menu_about:
                String[] libraries = getResources().getStringArray(R.array.libraries_ids);
                LibsBuilder builder = new LibsBuilder()
                        .withAboutAppName(getString(R.string.app_name))
                        .withAboutDescription(getString(R.string.app_description))
                        .withAboutIconShown(true)
                        .withActivityTitle(getString(R.string.menu_about))
                        .withAboutVersionShown(true)
                        .withLibraries(libraries)
                        .withAutoDetect(false)
                        .withLicenseShown(true)
                        .withFields(R.string.class.getFields());
                builder.start(this);
                break;
            default:
                if (item.getGroupId() == R.id.category_other_accounts) {
                    // Other accounts ground action
                    int accountIndex = item.getItemId() - OTHER_ACCOUNTS_GROUP_BASE_ID;
                    Account account = mAccounts.get(accountIndex);
                    if (account != null) {
                        mAccount = account;
                        setAnalyticsAccount(mAccount);
                        performAccountSwitch();
                    }
                }
                break;
        }
    }

    private void performNavigateTo() {
        final Menu menu = mBinding.drawerNavigationView.getMenu();
        final MenuItem item = menu.findItem(mModel.currentNavigationItemId);
        if (item == null || mAccount == null) {
            return;
        }

        switch (item.getItemId()) {
            case R.id.menu_trending:
                openTrendingFragment();
                break;

            case R.id.menu_following:
                openFollowingFragment();
                break;

            case R.id.menu_dashboard:
                openDashboardFragment();
                break;

            default:
                // Is a filter menu?
                mModel.filterQuery = getQueryFilterExpressionFromMenuItemId(item.getItemId());
                if (mModel.filterQuery != null) {
                    mModel.filterName = item.getTitle().toString()
                            .split(DrawerNavigationView.SEPARATOR)[0];
                    openFilterFragment(item.getItemId(), mModel.filterName, mModel.filterQuery);
                }
                break;
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateAccountCustomFilters() {
        // Remove all custom filters and re-add them
        final DrawerNavigationMenu menu =
                (DrawerNavigationMenu) mBinding.drawerNavigationView.getMenu();
        int myFiltersGroupIndex = menu.findGroupIndex(R.id.category_my_filters);
        MenuItem group = menu.getItem(myFiltersGroupIndex);
        SubMenu myFiltersSubMenu = group.getSubMenu();
        int count = myFiltersSubMenu.size() - 1;
        for (int i = count; i >= 0; i--) {
            ((DrawerNavigationSubMenu)myFiltersSubMenu).removeItemAt(i);
        }

        mCustomFilters = Preferences.getAccountCustomFilters(this, mAccount);
        if (mCustomFilters != null) {
            int i = 0;
            for (CustomFilter filter : mCustomFilters) {
                int id = MY_FILTERS_GROUP_BASE_ID + i;
                String title = filter.mName
                        + DrawerNavigationView.SEPARATOR
                        + filter.mQuery.toString()
                        + DrawerNavigationView.SEPARATOR
                        + "ic_close";
                MenuItem item = myFiltersSubMenu.add(group.getGroupId(), id, Menu.NONE, title);
                item.setIcon(R.drawable.ic_filter);
                item.setCheckable(true);
                i++;
            }
        }

        menu.setGroupVisible(R.id.category_my_filters,
                !mModel.isAccountExpanded && mCustomFilters != null && !mCustomFilters.isEmpty());
        mBinding.drawerNavigationView.setCheckedItem(mModel.currentNavigationItemId);
    }

    @SuppressLint("RestrictedApi")
    private void updateAccountsDrawerInfo() {
        // Remove all accounts and re-add them
        final DrawerNavigationMenu menu =
                (DrawerNavigationMenu) mBinding.drawerNavigationView.getMenu();
        int otherAccountGroupIndex = menu.findGroupIndex(R.id.category_other_accounts);
        MenuItem group = menu.getItem(otherAccountGroupIndex);
        SubMenu otherAccountsSubMenu = group.getSubMenu();
        int count = otherAccountsSubMenu.size() - 1;
        for (int i = count; i > 0; i--) {
            ((DrawerNavigationSubMenu) otherAccountsSubMenu).removeItemAt(i);
        }
        int i = 0;
        for (Account account : mAccounts) {
            // Current account
            if (mAccount.isSameAs(account)) {
                i++;
                continue;
            }

            int id = OTHER_ACCOUNTS_GROUP_BASE_ID + i;
            String title = account.getAccountDisplayName()
                    + DrawerNavigationView.SEPARATOR
                    + account.getRepositoryDisplayName()
                    + DrawerNavigationView.SEPARATOR
                    + "ic_delete"
                    + DrawerNavigationView.SEPARATOR
                    + "false";
            MenuItem item = otherAccountsSubMenu.add(group.getGroupId(), id, Menu.NONE, title);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                item.setIconTintList(ContextCompat.getColorStateList(
                        MainActivity.this,
                        R.color.accent));
            }

            RviewImageHelper.bindAvatar(this, account, account.mAccount, item,
                    RviewImageHelper.getDefaultAvatar(this, R.color.accent));
            i++;
        }

        if (mAccount != null) {
            updateAccountStatus();
        }
    }

    private void updateCurrentAccountDrawerInfo() {
        mModel.accountName = mAccount.getAccountDisplayName();
        mModel.accountRepository = mAccount.getRepositoryDisplayName();
        mHeaderDrawerBinding.setModel(mModel);
        mHeaderDrawerBinding.executePendingBindings();

        RviewImageHelper.bindAvatar(this, mAccount.mAccount, mHeaderDrawerBinding.accountAvatar,
                RviewImageHelper.getDefaultAvatar(this, android.R.color.white));
    }

    @SuppressLint("RestrictedApi")
    private void updateAccountStatus() {
        final DrawerNavigationMenu menu =
                (DrawerNavigationMenu) mBinding.drawerNavigationView.getMenu();
        String title = getString(R.string.menu_account_status);
        if (!TextUtils.isEmpty(mAccount.mAccount.status)) {
            title += DrawerNavigationView.SEPARATOR +
                    EmojiHelper.resolveEmojiStatus(this, mAccount.mAccount.status);
        }
        MenuItem item = menu.findItem(R.id.menu_account_status);
        item.setTitle(title);
    }

    private void performAccountSwitch() {
        // Check that we are in a valid status before update the ui
        if (mAccount == null) {
            if (mAccounts.size() == 0) {
                return;
            }

            // Use the first one account
            mAccount = mAccounts.get(0);
            setAnalyticsAccount(mAccount);
        }
        Preferences.setAccount(this, mAccount);
        Formatter.refreshCachedPreferences(this);
        fetchAccountStatus(mAccount);
        AnalyticsManagerImpl.instance().accountSelected(this, mAccount);

        // Refresh the ui
        updateCurrentAccountDrawerInfo();
        updateAccountCustomFilters();
        updateAccountsDrawerInfo();
        internalPerformShowAccount(false);

        // And navigate to the default menu
        requestNavigateTo(Preferences.getAccountHomePageId(this, mAccount), true, true);
    }

    private void requestAccountDeletion(final Account account) {
        if (account == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.account_deletion_title)
                .setMessage(R.string.account_deletion_message)
                .setPositiveButton(R.string.action_delete, (dialogInterface, i) ->
                        Message.obtain(mUiHandler, MESSAGE_DELETE_ACCOUNT, account.getAccountHash())
                                .sendToTarget())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
    }

    @SuppressWarnings("Convert2streamapi")
    private void performDeleteAccount(String accountHash) {
        List<Account> accounts = Preferences.getAccounts(this);
        for (Account acct : accounts) {
            if (acct.getAccountHash().equals(accountHash)) {
                boolean currentAccount = mAccount != null &&
                        mAccount.getAccountHash().equals(acct.getAccountHash());
                if (currentAccount) {
                    AnalyticsManagerImpl.instance().accountEvent(this, mAccount, false);
                    performDeleteAccount(mAccount, true);
                    internalPerformShowAccount(false);
                } else {
                    AnalyticsManagerImpl.instance().accountEvent(this, acct, false);
                    performDeleteAccount(acct, false);
                }

                // Close the drawer
                if (mBinding.drawerLayout != null) {
                    if (mBinding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        mBinding.drawerLayout.closeDrawer(GravityCompat.START, true);
                        mBinding.drawerNavigationView.updateNavigationView();
                    }
                }
                break;
            }
        }
    }

    private void performDeleteAccount(Account acct, boolean isCurrent) {
        // Remove the account
        boolean accountDeleted = false;
        int count = mAccounts.size();
        for (int i = 0; i < count; i++) {
            Account account = mAccounts.get(i);
            if (account.isSameAs(acct)) {
                accountDeleted = true;
                mAccounts.remove(i);
                break;
            }
        }

        if (!accountDeleted) {
            showError(R.string.account_deletion_confirm_message);
            return;
        }

        // Remove the account
        Preferences.removeAccount(this, acct);
        if (isCurrent) {
            Preferences.setAccount(this, null);
        }
        Formatter.refreshCachedPreferences(this);
        Preferences.removeAccountPreferences(this, acct);
        CacheHelper.removeAccountCacheDir(this, acct);
        NotificationEntity.deleteAccountNotifications(this, acct.getAccountHash());
        NotificationsHelper.deleteNotificationChannel(this, acct);

        // Unregister the url handling for this repository if no other account for the
        // same repository is active
        if (ModelHelper.canAccountHandleUrls(this, acct)) {
            boolean unregisterUrlHandler = true;
            List<Account> accounts = Preferences.getAccounts(getApplicationContext());
            for (Account account : accounts) {
                if (UriHelper.sanitizeEndpoint(acct.mRepository.mUrl).equals(
                            UriHelper.sanitizeEndpoint(account.mRepository.mUrl))) {
                    unregisterUrlHandler = false;
                    break;
                }
            }
            if (unregisterUrlHandler) {
                ModelHelper.setAccountUrlHandlingStatus(getApplicationContext(), acct, false);
            }
        }

        // Show message
        Snackbar.make(mBinding.getRoot(), R.string.account_deletion_confirm_message,
                Snackbar.LENGTH_SHORT).show();

        if (isCurrent) {
            mAccount = null;
            setAnalyticsAccount(null);

            performAccountSwitch();

            // Have any account? No, then launch setup account wizard
            launchAddAccountIfNeeded();
        } else {
            updateAccountsDrawerInfo();
        }
    }

    private void openAccountSetup() {
        mUiHandler.post(() -> {
            if (mAccount != null) {
                Intent i = new Intent(this, AuthorizationAccountSetupActivity.class);
                startActivity(i);
                internalPerformShowAccount(false);
            }
        });
    }

    private void openAccountSettings() {
        mUiHandler.post(() -> {
            if (mAccount != null) {
                Intent i = new Intent(MainActivity.this, AccountSettingsActivity.class);
                startActivityForResult(i, REQUEST_ACCOUNT_SETTINGS);
                internalPerformShowAccount(false);
            }
        });
    }

    private void openAccountNotifications() {
        mUiHandler.post(() -> {
            if (mAccount != null) {
                Intent i = new Intent(MainActivity.this, NotificationsActivity.class);
                i.putExtra(Constants.EXTRA_ACCOUNT_HASH, mAccount.getAccountHash());
                i.putExtra(Constants.EXTRA_HAS_PARENT, false);
                startActivity(i);
                internalPerformShowAccount(false);
            }
        });
    }

    private void openAccountStatusChooser() {
        mUiHandler.post(() -> {
            if (mAccount != null) {
                SetAccountStatusDialogFragment fragment = SetAccountStatusDialogFragment.newInstance();
                fragment.show(getSupportFragmentManager(), SetAccountStatusDialogFragment.TAG);
            }
        });

    }

    private void openAccountStats() {
        mUiHandler.post(() -> {
            if (mAccount != null) {
                ChangeQuery filter = new ChangeQuery().owner(
                        ModelHelper.getSafeAccountOwner(mAccount.mAccount));
                String title = getString(R.string.account_details);
                String displayName = ModelHelper.getAccountDisplayName(mAccount.mAccount);
                String extra = SerializationManager.getInstance().toJson(mAccount.mAccount);
                ActivityHelper.openStatsActivity(
                        this, title, displayName, StatsFragment.ACCOUNT_STATS,
                        String.valueOf(mAccount.mAccount.accountId), filter, extra);
            }
        });
    }

    private void openAccountServerInfo() {
        mUiHandler.post(() -> {
            if (mAccount != null) {
                ServerInfoDialogFragment fragment = ServerInfoDialogFragment.newInstance();
                fragment.show(getSupportFragmentManager(), ServerInfoDialogFragment.TAG);
            }
        });
    }

    private void openDashboardFragment() {
        mModel.filterName = getString(R.string.menu_dashboard);
        mModel.filterQuery = null;

        Fragment newFragment = DashboardFragment.newInstance();
        openFragment(-1, getString(R.string.menu_dashboard), null, newFragment);
    }

    private void openTrendingFragment() {
        mModel.filterName = getString(R.string.menu_trending);
        mModel.filterQuery = null;

        Fragment newFragment = TrendingChangeListFragment.newInstance();
        openFragment(-1, getString(R.string.menu_trending), null, newFragment);
    }

    private void openFollowingFragment() {
        mModel.filterName = getString(R.string.menu_following);
        mModel.filterQuery = null;

        Fragment newFragment = FollowingChangeListFragment.newInstance();
        openFragment(-1, getString(R.string.menu_following), null, newFragment);
    }

    private void openFilterFragment(int id, CharSequence title, String filter) {
        Fragment newFragment = ChangeListByFilterFragment.newInstance(filter, false, true, true);
        openFragment(id, title, filter, newFragment);
    }

    private void openFragment(int id, CharSequence title, String filter, Fragment fragment) {
        // Setup the title and tabs
        invalidateTabs();
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title);
            getSupportActionBar().setSubtitle(filter);
        }

        // Open the filter fragment
        mModel.selectedChangeId = INVALID_ITEM;
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(false);
        Fragment oldFragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_LIST);
        if (oldFragment != null) {
            tx.remove(oldFragment);
        }
        oldFragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DETAILS);
        if (oldFragment != null) {
            tx.remove(oldFragment);
        }
        tx.replace(R.id.content, fragment, FRAGMENT_TAG_LIST).commit();

        // Select the drawer item
        if (id > 0) {
            mBinding.drawerNavigationView.setCheckedItem(id);
            mModel.currentNavigationItemId = id;
        }
    }

    private void onNavigationMenuItemClick(int menuId) {
        if (mCustomFilters != null &&
                menuId >= MY_FILTERS_GROUP_BASE_ID && menuId < OTHER_ACCOUNTS_GROUP_BASE_ID) {
            performDeleteCustomFilter(menuId);
        } else if (menuId >= OTHER_ACCOUNTS_GROUP_BASE_ID) {
            int accountIndex = menuId - OTHER_ACCOUNTS_GROUP_BASE_ID;
            Account account = mAccounts.get(accountIndex);
            if (account != null) {
                requestAccountDeletion(account);
            }
        }
    }

    private void performDeleteCustomFilter(int menuId) {
        CustomFilter filter = mCustomFilters.get(menuId - MY_FILTERS_GROUP_BASE_ID);
        mCustomFilters.remove(filter);
        Preferences.setAccountCustomFilters(this, mAccount, mCustomFilters);

        if (mModel.currentNavigationItemId == menuId) {
            int defaultMenu = Preferences.getAccountHomePageId(this, mAccount);
            requestNavigateTo(defaultMenu, true, true);
        }
        updateAccountCustomFilters();
    }

    private boolean launchAddAccountIfNeeded() {
        if (mAccount == null) {
            Intent i = new Intent(this, SetupAccountActivity.class);
            startActivityForResult(i, REQUEST_WIZARD);
            return true;
        }
        return false;
    }

    private String getQueryFilterExpressionFromMenuItemId(int itemId) {
        // Custom filters
        if (mCustomFilters != null &&
                itemId >= MY_FILTERS_GROUP_BASE_ID && itemId <= OTHER_ACCOUNTS_GROUP_BASE_ID) {
            int filterIndex = itemId - MY_FILTERS_GROUP_BASE_ID;
            CustomFilter customFilter = mCustomFilters.get(filterIndex);
            return customFilter.mQuery.toString();
        }

        // Predefined filters
        String[] names = getResources().getStringArray(R.array.query_filters_ids_names);
        String[] filters = getResources().getStringArray(R.array.query_filters_values);
        int count = names.length;
        for (int i = 0; i < count; i++) {
            int id = getResources().getIdentifier(names[i], "id", getPackageName());
            if (itemId == id) {
                return filters[i];
            }
        }

        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void onRefreshEnd(Fragment from, T result) {
        super.onRefreshEnd(from, result);
        if (result == null) {
            return;
        }

        if (mIsTwoPane && result instanceof List) {
            Fragment current = getSupportFragmentManager().findFragmentByTag(
                    FRAGMENT_TAG_LIST);
            if (current instanceof PageableFragment) {
                current = ((PageableFragment) current).getCurrentFragment();
                if (current != null && !current.equals(from)) {
                    // This is not the visible fragment. ignore its results
                    return;
                }
            } else {
                if (!current.equals(from)) {
                    // This is not the visible fragment. ignore its results
                    return;
                }
            }

            List<ChangeInfo> changes = (List<ChangeInfo>) result;
            if (!changes.isEmpty() && mModel.selectedChangeId == INVALID_ITEM) {
                onChangeItemPressed(changes.get(0));
            }
        } else if (result instanceof ChangeInfo) {
            mModel.selectedChangeId = ((ChangeInfo) result).legacyChangeId;
        }
    }

    private void setupAccountUrlHandlingStatus(Account account, boolean status) {
        Preferences.setAccountHandleLinks(this, account, status);
        if (ModelHelper.canAccountHandleUrls(getApplicationContext(), account)) {
            ModelHelper.setAccountUrlHandlingStatus(getApplicationContext(), account, status);
        }
    }

    private void fetchAccountStatus(Account account) {
        // This is running while the app is in foreground so there is not risk on
        // perform this operation. In addition, we need the account information asap, so
        // just try to start the service directly.
        try {
            Intent intent = new Intent(this, AccountStatusFetcherService.class);
            intent.setAction(AccountStatusFetcherService.ACCOUNT_STATUS_FETCHER_ACTION);
            intent.putExtra(AccountStatusFetcherService.EXTRA_ACCOUNT, account.getAccountHash());
            startService(intent);
        } catch (IllegalStateException ex) {
            Log.w(TAG, "Can't start account fetcher service.", ex);
        }
    }

    @Override
    public void performReload() {
        Message.obtain(mUiHandler, MESSAGE_NAVIGATE_TO).sendToTarget();
    }
}

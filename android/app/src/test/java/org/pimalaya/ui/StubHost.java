package org.pimalaya.ui;

/** A host with no chrome, for tests that need an activity only. */
public class StubHost extends Host {
    @Override
    public void showBarTitle(boolean shown) {}

    @Override
    public ListHeader headerOf(int panel) {
        return null;
    }

    @Override
    public void foldFab(boolean folded) {}

    @Override
    public int titleOf(int panel) {
        return 0;
    }

    @Override
    public void show(int panel) {}

    @Override
    public void showBack(int panel) {}

    @Override
    public void openOverlay(int panel) {}

    @Override
    public void closeOverlay(int panel) {}

    @Override
    public void applyChrome(int panel) {}

    @Override
    public void hideKeyboard() {}

    @Override
    public void setFabEnabled(int id, boolean enabled) {}

    @Override
    public void setAuthLoading(int buttonId, int progressId, boolean loading) {}

    @Override
    public void gateSave(boolean ready, int icon, int description) {}

    @Override
    public void showDomainTitle(int panel) {}

    @Override
    public void updateBarTitle(String title) {}

    @Override
    public void setListBusy(boolean busy) {}

    @Override
    public void setSending(boolean sending) {}

    @Override
    public void showMailBadge(int unread) {}

    @Override
    public void reloadHome() {}
}

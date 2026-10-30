package org.pimalaya.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

/**
 * A recycling adapter over {@link CardSections}: its headers, our rows.
 *
 * <p>Header taps stay each list's choice: BaseAdapter's default (all enabled)
 * unless it overrides {@link #isEnabled}.
 */
public abstract class SectionsAdapter<T> extends BaseAdapter {
    private final CardSections<T> sections;

    protected SectionsAdapter(CardSections<T> sections) {
        this.sections = sections;
    }

    @Override
    public int getCount() {
        return sections.size();
    }

    @Override
    public Object getItem(int position) {
        return sections.row(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    /** A row, a header, or a header opening a group, each recycled apart. */
    @Override
    public int getItemViewType(int position) {
        if (!sections.isHeader(position)) {
            return 0;
        }
        return sections.opensGroup(position) ? 2 : 1;
    }

    @Override
    public final View getView(int position, View recycled, ViewGroup parent) {
        if (sections.isHeader(position)) {
            return sections.headerView(position, recycled, parent);
        }
        return rowView(position, recycled, parent);
    }

    /** A row's view at a position no header holds, recycled when it can be. */
    protected abstract View rowView(int position, View recycled, ViewGroup parent);
}

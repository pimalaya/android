package org.pimalaya.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

/**
 * A recycling adapter over a {@link CardSections} layout: the headers
 * and their view type are the layout's, the rows the subclass's.
 *
 * <p>Whether a header takes taps stays with each list, so it keeps
 * BaseAdapter's default (every position enabled) unless it overrides
 * {@link #isEnabled}.
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
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return sections.isHeader(position) ? 1 : 0;
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

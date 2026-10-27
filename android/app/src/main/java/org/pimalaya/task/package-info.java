/**
 * Tasks: the calendars' to-dos organised the GTD way, and the Tasks tab.
 *
 * <p>No store of its own (cairn/changes/gtd): a task is a VTODO in a calendar
 * collection, its list in {@code X-PIMALAYA-GTD}, its contexts, project and
 * source on standard properties. {@link TaskStore} reads them, {@link
 * TaskWrites} stages the writes, {@link TasksList} is the tab, {@link
 * CaptureSheet}, {@link Clarify} and {@link WeeklyReview} the steps.
 */
package org.pimalaya.task;

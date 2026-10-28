/**
 * Tasks: the calendars' to-dos organised the GTD way, and the Tasks tab.
 *
 * <p>No store of its own (cairn/changes/gtd-by-categories): a task is a VTODO
 * in a calendar collection, its state one of three reserved {@code
 * CATEGORIES} values ({@code inbox}, {@code waiting}, {@code someday}), its
 * other categories, parent and source on standard properties. {@link
 * TaskStore} reads them, {@link TaskWrites} stages the writes, {@link
 * TasksList} is the tab, {@link CaptureSheet}, {@link Clarify} and {@link
 * WeeklyReview} the steps.
 */
package org.pimalaya.task;

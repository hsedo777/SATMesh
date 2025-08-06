package org.sedo.satmesh.utils;

import android.content.Context;

import org.sedo.satmesh.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Utility class containing static methods.
 */
public class Utils {

	/**
	 * The maximum allowed length for a username.
	 */
	private static final int USERNAME_MAX_LENGTH = 60;
	/**
	 * The minimum allowed length for a username.
	 */
	private static final int USERNAME_MIN_LENGTH = 2;

	/**
	 * Private constructor to prevent instantiation of this utility class.
	 */
	private Utils() {
	}

	/**
	 * Formats a timestamp (UTC) into a localized, human-readable string based on how recent it is.
	 * <ul>
	 * <li> Today: shows time only (e.g., "14:35")</li>
	 * <li> Yesterday: localized string (e.g., "Yesterday")</li>
	 * <li> Within last 7 days: day of week (e.g., "Monday")</li>
	 * <li> Same month: day and month (e.g., "5 June")</li>
	 * <li> Same year: abbreviated day, day of month, month (e.g., "Tue 5 June")</li>
	 * <li> Else: localized full date (e.g., "05 Jun 2023")</li>
	 * </ul>
	 *
	 * @param context   The application context for accessing localized resources.
	 * @param timestamp A UTC-based timestamp in milliseconds since epoch.
	 * @return A human-friendly, localized string representing the timestamp.
	 */
	public static String formatTimestamp(Context context, long timestamp) {
		Locale locale = Locale.getDefault();

		Calendar now = Calendar.getInstance(); // local time
		Calendar messageCal = Calendar.getInstance();
		messageCal.setTimeInMillis(timestamp);

		// Check if it's today
		if (isSameDay(now, messageCal)) {
			return new SimpleDateFormat("HH:mm", locale).format(messageCal.getTime());
		}

		// Check if it's yesterday
		Calendar yesterday = (Calendar) now.clone();
		yesterday.add(Calendar.DAY_OF_YEAR, -1);
		if (isSameDay(yesterday, messageCal)) {
			return context.getString(R.string.yesterday); // Localized "Yesterday"
		}

		// Check if it's within the last 7 days
		Calendar oneWeekAgo = (Calendar) now.clone();
		oneWeekAgo.add(Calendar.DAY_OF_YEAR, -7);
		if (messageCal.after(oneWeekAgo)) {
			return new SimpleDateFormat("EEEE", locale).format(messageCal.getTime());
		}

		// Check if it's the same month in the same year
		if (now.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
				now.get(Calendar.MONTH) == messageCal.get(Calendar.MONTH)) {
			return new SimpleDateFormat("d MMMM", locale).format(messageCal.getTime());
		}

		// Check if it's the same year
		if (now.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR)) {
			SimpleDateFormat fullFormat = new SimpleDateFormat("EEE d MMMM", locale); // Tue 5 June
			return fullFormat.format(messageCal.getTime());
		}

		// Else, return full date (e.g., 05 Jun 2023)
		SimpleDateFormat defaultFormat = new SimpleDateFormat("dd MMM yyyy", locale);
		return defaultFormat.format(messageCal.getTime());
	}

	/**
	 * Formats a timestamp (UTC) into a more granular interval-based format.
	 * <ul>
	 * <li>&lt; 24 hours: "HH:mm"</li>
	 * <li>Yesterday: "Yesterday HH:mm" (localized)</li>
	 * <li>&lt; 7 days: "EEE HH:mm"</li>
	 * <li>&lt; 1 month: "EEEE dd"</li>
	 * <li>&lt; 1 year: "dd MMM"</li>
	 * <li>Else: "dd MMM yyyy"</li>
	 * </ul>
	 *
	 * @param context   The application context.
	 * @param timestamp A UTC-based timestamp in milliseconds since epoch.
	 * @return A localized human-readable timestamp string.
	 */
	public static String formatTimestampByInterval(Context context, long timestamp) {
		Locale locale = Locale.getDefault();

		Calendar now = Calendar.getInstance();
		Calendar messageCal = Calendar.getInstance();
		messageCal.setTimeInMillis(timestamp);

		long deltaMillis = now.getTimeInMillis() - timestamp;
		long hours = deltaMillis / (1000 * 60 * 60);
		long days = deltaMillis / (1000 * 60 * 60 * 24);

		if (hours < 24) {
			return new SimpleDateFormat("HH:mm", locale).format(messageCal.getTime());
		}

		Calendar yesterday = (Calendar) now.clone();
		yesterday.add(Calendar.DAY_OF_YEAR, -1);
		if (isSameDay(yesterday, messageCal)) {
			return context.getString(R.string.yesterday) + " " + new SimpleDateFormat("HH:mm", locale).format(messageCal.getTime());
		}
		if (days < 7) {
			return new SimpleDateFormat("EEE HH:mm", locale).format(messageCal.getTime());
		}

		Calendar oneMonthAgo = (Calendar) now.clone();
		oneMonthAgo.add(Calendar.MONTH, -1);
		if (messageCal.after(oneMonthAgo)) {
			return new SimpleDateFormat("EEEE dd", locale).format(messageCal.getTime());
		}

		Calendar oneYearAgo = (Calendar) now.clone();
		oneYearAgo.add(Calendar.YEAR, -1);
		if (messageCal.after(oneYearAgo)) {
			return new SimpleDateFormat("dd MMM", locale).format(messageCal.getTime());
		}
		return new SimpleDateFormat("dd MMM yyyy", locale).format(messageCal.getTime());
	}

	/**
	 * Checks if two {@link Calendar} instances represent the same day (ignoring time).
	 *
	 * @param cal1 The first calendar instance.
	 * @param cal2 The second calendar instance.
	 * @return {@code true} if both calendars represent the same year and day of the year,
	 * {@code false} otherwise.
	 */
	private static boolean isSameDay(Calendar cal1, Calendar cal2) {
		return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
				&& cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
	}

	/**
	 * Validates if the given username meets the defined criteria.
	 * A valid username must not be null or empty after trimming, and its length
	 * must be between {@link #USERNAME_MIN_LENGTH} and {@link #USERNAME_MAX_LENGTH} inclusive.
	 *
	 * @param username The username string to validate.
	 * @return {@code true} if the username is valid, {@code false} otherwise.
	 */
	public static boolean isUsernameValid(String username) {
		String trimmedUsername = username != null ? username.trim() : null;
		return trimmedUsername != null && !trimmedUsername.isEmpty() &&
				trimmedUsername.length() >= USERNAME_MIN_LENGTH && trimmedUsername.length() <= USERNAME_MAX_LENGTH;
	}
}

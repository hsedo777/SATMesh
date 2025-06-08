package org.sedo.satmesh.utils;

import android.content.Context;

import org.sedo.satmesh.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Utility class containing static methods.
 */
public class Utils {

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
			SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", locale);
			return timeFormat.format(new Date(timestamp));
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
			SimpleDateFormat weekdayFormat = new SimpleDateFormat("EEEE", locale); // Monday, etc.
			return weekdayFormat.format(messageCal.getTime());
		}

		// Check if it's the same month in the same year
		if (now.get(Calendar.YEAR) == messageCal.get(Calendar.YEAR) &&
				now.get(Calendar.MONTH) == messageCal.get(Calendar.MONTH)) {
			SimpleDateFormat dayMonthFormat = new SimpleDateFormat("d MMMM", locale); // 5 June
			return dayMonthFormat.format(messageCal.getTime());
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

	private static boolean isSameDay(Calendar cal1, Calendar cal2) {
		return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
				&& cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
	}
}

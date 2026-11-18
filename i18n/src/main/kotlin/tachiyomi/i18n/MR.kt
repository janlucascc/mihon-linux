package tachiyomi.i18n

import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/**
 * Mihon Resources (MR) - Internationalization support using Java ResourceBundle.
 * 
 * Usage: 
 * - MR["key"] or MR.getString("key") for any string
 * - MR.strings.key_name for commonly used strings (type-safe)
 * - MR.format.dateFormatter() for localized date formatting
 * - MR.orientation.componentOrientation for RTL support
 * 
 * Supported locales:
 * - en (default) - English
 * - pt_BR - Portuguese (Brazil)
 * - es - Spanish
 * - ja - Japanese
 * - zh_CN - Chinese (Simplified)
 */
object MR {

    private val bundleCache = ConcurrentHashMap<String, ResourceBundle>()
    private var currentLocale: Locale = Locale.getDefault()
    
    /**
     * Gets the current locale being used for string resolution.
     */
    var locale: Locale
        get() = currentLocale
        set(value) {
            currentLocale = value
            bundleCache.clear()
        }

    /**
     * Sets the locale by language code (e.g., "en", "pt_BR", "es", "ja", "zh_CN").
     * This clears the bundle cache to force reload with new locale.
     */
    fun setLocaleByTag(languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag.replace('_', '-'))
        currentLocale = locale
        bundleCache.clear()
    }

    /**
     * Sets the locale directly.
     */
    fun setLocaleDirect(locale: Locale) {
        currentLocale = locale
        bundleCache.clear()
    }

    /**
     * Resets to system default locale.
     */
    fun resetToSystemLocale() {
        currentLocale = Locale.getDefault()
        bundleCache.clear()
    }

    /**
     * Gets a string resource by key, with optional format arguments.
     * This is the primary way to access strings: MR["key"] or MR.getString("key")
     */
    operator fun get(key: String): String = getString(key)

    fun getString(key: String, vararg formatArgs: Any): String {
        val bundle = getBundle()
        val value = try {
            bundle.getString(key)
        } catch (e: java.util.MissingResourceException) {
            // Fallback to key if not found
            key
        }
        return if (formatArgs.isNotEmpty()) {
            try {
                java.lang.String.format(currentLocale, value, *formatArgs)
            } catch (e: Exception) {
                value
            }
        } else {
            value
        }
    }

    private fun getBundle(): ResourceBundle {
        val cacheKey = currentLocale.toLanguageTag()
        return bundleCache.computeIfAbsent(cacheKey) {
            ResourceBundle.getBundle("tachiyomi.i18n.messages", currentLocale)
        }
    }

    /**
     * Checks if a key exists in the current locale's bundle.
     */
    fun containsKey(key: String): Boolean {
        val bundle = getBundle()
        return bundle.containsKey(key)
    }

    /**
     * Gets all available locales that have resource bundles.
     */
    fun getAvailableLocales(): List<Locale> {
        return listOf(
            Locale.ENGLISH,
            Locale("pt", "BR"),
            Locale("es"),
            Locale("ja"),
            Locale("zh", "CN")
        )
    }

    /**
     * Gets the display name of a locale in the current locale.
     */
    fun getLocaleDisplayName(locale: Locale): String {
        return locale.getDisplayName(currentLocale)
    }

    /**
     * String resource accessor for commonly used strings (type-safe).
     * For all other strings, use MR["key"] or MR.getString("key").
     */
    object strings {
        // App
        val app_name: String get() = MR["app_name"]
        val app_version: String get() = MR["app_version"]

        // Generic - most commonly used
        val on: String get() = MR["on"]
        val off: String get() = MR["off"]
        val selected: String get() = MR["selected"]
        val not_selected: String get() = MR["not_selected"]
        val loading: String get() = MR["loading"]
        val cancel: String get() = MR["cancel"]
        val ok: String get() = MR["ok"]
        val yes: String get() = MR["yes"]
        val no: String get() = MR["no"]
        val save: String get() = MR["save"]
        val apply: String get() = MR["apply"]
        val reset: String get() = MR["reset"]
        val retry: String get() = MR["retry"]
        val close: String get() = MR["close"]
        val back: String get() = MR["back"]
        val next: String get() = MR["next"]
        val previous: String get() = MR["previous"]
        val more: String get() = MR["more"]
        val menu: String get() = MR["menu"]
        val search: String get() = MR["search"]
        val filter: String get() = MR["filter"]
        val sort: String get() = MR["sort"]
        val edit: String get() = MR["edit"]
        val add: String get() = MR["add"]
        val remove: String get() = MR["remove"]
        val delete: String get() = MR["delete"]
        val create: String get() = MR["create"]
        val update: String get() = MR["update"]
        val refresh: String get() = MR["refresh"]
        val open: String get() = MR["open"]
        val import: String get() = MR["import"]
        val export: String get() = MR["export"]
        val download: String get() = MR["download"]
        val share: String get() = MR["share"]
        val copy: String get() = MR["copy"]
        val paste: String get() = MR["paste"]
        val clear: String get() = MR["clear"]
        val enable: String get() = MR["enable"]
        val disable: String get() = MR["disable"]
        val configure: String get() = MR["configure"]
        val settings: String get() = MR["settings"]
        val help: String get() = MR["help"]
        val about: String get() = MR["about"]
        val version: String get() = MR["version"]
        val website: String get() = MR["website"]
        val licenses: String get() = MR["licenses"]
        val privacy_policy: String get() = MR["privacy_policy"]
        val check_for_updates: String get() = MR["check_for_updates"]
        val whats_new: String get() = MR["whats_new"]
        val getting_started_guide: String get() = MR["getting_started_guide"]
        val help_translate: String get() = MR["help_translate"]
        val report_issue: String get() = MR["report_issue"]

        // Models
        val name: String get() = MR["name"]
        val categories: String get() = MR["categories"]
        val manga: String get() = MR["manga"]
        val chapters: String get() = MR["chapters"]
        val track: String get() = MR["track"]
        val history: String get() = MR["history"]
        val scanlator: String get() = MR["scanlator"]
        val author: String get() = MR["author"]
        val artist: String get() = MR["artist"]
        val status: String get() = MR["status"]
        val title: String get() = MR["title"]
        val description: String get() = MR["description"]
        val cover: String get() = MR["cover"]
        val source: String get() = MR["source"]
        val language: String get() = MR["language"]
        val genre: String get() = MR["genre"]
        val score: String get() = MR["score"]
        val date: String get() = MR["date"]
        val unread: String get() = MR["unread"]
        val completed: String get() = MR["completed"]
        val ongoing: String get() = MR["ongoing"]
        val unknown: String get() = MR["unknown"]
        val licensed: String get() = MR["licensed"]
        val paused: String get() = MR["paused"]
        val dropped: String get() = MR["dropped"]
        val on_hold: String get() = MR["on_hold"]
        val plan_to_read: String get() = MR["plan_to_read"]
        val repeating: String get() = MR["repeating"]

        // Security & Privacy
        val lock_always: String get() = MR["lock_always"]
        val lock_when_idle: String get() = MR["lock_when_idle"]
        val lock_never: String get() = MR["lock_never"]
        val hide_notification_content: String get() = MR["hide_notification_content"]
        val secure_screen: String get() = MR["secure_screen"]
        val secure_screen_summary: String get() = MR["secure_screen_summary"]
        val firebase_summary: String get() = MR["firebase_summary"]
        val pref_allowed_content_warnings: String get() = MR["pref_allowed_content_warnings"]
        val pref_apply_content_warnings_to_installed: String get() = MR["pref_apply_content_warnings_to_installed"]
        val pref_apply_content_warnings_to_installed_summary: String get() = MR["pref_apply_content_warnings_to_installed_summary"]
        val content_warnings_info: String get() = MR["content_warnings_info"]
        val relative_time_today: String get() = MR["relative_time_today"]
        val pref_incognito_mode: String get() = MR["pref_incognito_mode"]
        val pref_incognito_mode_summary: String get() = MR["pref_incognito_mode_summary"]
        val pref_incognito_mode_extension_summary: String get() = MR["pref_incognito_mode_extension_summary"]
        val notification_incognito_text: String get() = MR["notification_incognito_text"]
        val downloaded_only_summary: String get() = MR["downloaded_only_summary"]
        val pref_clear_history: String get() = MR["pref_clear_history"]

        // Screen titles
        val label_more: String get() = MR["label_more"]
        val label_settings: String get() = MR["label_settings"]
        val label_download_queue: String get() = MR["label_download_queue"]
        val label_library: String get() = MR["label_library"]
        val label_recent_updates: String get() = MR["label_recent_updates"]
        val label_upcoming: String get() = MR["label_upcoming"]
        val label_recent_manga: String get() = MR["label_recent_manga"]
        val label_sources: String get() = MR["label_sources"]
        val label_backup: String get() = MR["label_backup"]
        val label_data_storage: String get() = MR["label_data_storage"]
        val label_stats: String get() = MR["label_stats"]
        val label_migration: String get() = MR["label_migration"]
        val label_extensions: String get() = MR["label_extensions"]
        val label_extension_info: String get() = MR["label_extension_info"]
        val label_help: String get() = MR["label_help"]
        val label_support_us: String get() = MR["label_support_us"]
        val label_default: String get() = MR["label_default"]
        val label_warning: String get() = MR["label_warning"]
        val label_auto: String get() = MR["label_auto"]
        val label_local: String get() = MR["label_local"]
        val label_downloaded: String get() = MR["label_downloaded"]
        val label_downloaded_only: String get() = MR["label_downloaded_only"]
        val label_network: String get() = MR["label_network"]
        val label_data: String get() = MR["label_data"]
        val label_background_activity: String get() = MR["label_background_activity"]
        val label_started: String get() = MR["label_started"]
        val label_read_chapters: String get() = MR["label_read_chapters"]
        val label_tracked_titles: String get() = MR["label_tracked_titles"]
        val label_total_chapters: String get() = MR["label_total_chapters"]
        val label_mean_score: String get() = MR["label_mean_score"]
        val label_read_duration: String get() = MR["label_read_duration"]
        val label_titles_section: String get() = MR["label_titles_section"]
        val label_completed_titles: String get() = MR["label_completed_titles"]
        val label_titles_in_global_update: String get() = MR["label_titles_in_global_update"]
        val label_tracker_section: String get() = MR["label_tracker_section"]
        val label_used: String get() = MR["label_used"]
        val label_overview_section: String get() = MR["label_overview_section"]
        val label_about: String get() = MR["label_about"]

        // Common Actions
        val action_settings: String get() = MR["action_settings"]
        val action_menu: String get() = MR["action_menu"]
        val action_filter: String get() = MR["action_filter"]
        val action_filter_bookmarked: String get() = MR["action_filter_bookmarked"]
        val action_filter_tracked: String get() = MR["action_filter_tracked"]
        val action_filter_unread: String get() = MR["action_filter_unread"]
        val action_filter_empty: String get() = MR["action_filter_empty"]
        val action_filter_interval_custom: String get() = MR["action_filter_interval_custom"]
        val action_sort_alpha: String get() = MR["action_sort_alpha"]
        val action_sort_count: String get() = MR["action_sort_count"]
        val action_sort_total: String get() = MR["action_sort_total"]
        val action_sort_last_read: String get() = MR["action_sort_last_read"]
        val action_sort_last_manga_update: String get() = MR["action_sort_last_manga_update"]
        val action_sort_unread_count: String get() = MR["action_sort_unread_count"]
        val action_sort_latest_chapter: String get() = MR["action_sort_latest_chapter"]
        val action_sort_chapter_fetch_date: String get() = MR["action_sort_chapter_fetch_date"]
        val action_sort_date_added: String get() = MR["action_sort_date_added"]
        val action_sort_tracker_score: String get() = MR["action_sort_tracker_score"]
        val action_sort_random: String get() = MR["action_sort_random"]
        val action_search: String get() = MR["action_search"]
        val action_search_hint: String get() = MR["action_search_hint"]
        val action_search_settings: String get() = MR["action_search_settings"]
        val action_global_search: String get() = MR["action_global_search"]
        val action_global_search_hint: String get() = MR["action_global_search_hint"]
        val action_global_search_query: String get() = MR["action_global_search_query"]
        val action_select_all: String get() = MR["action_select_all"]
        val action_select_inverse: String get() = MR["action_select_inverse"]
        val action_mark_as_read: String get() = MR["action_mark_as_read"]
        val action_mark_as_unread: String get() = MR["action_mark_as_unread"]
        val action_mark_previous_as_read: String get() = MR["action_mark_previous_as_read"]
        val action_download: String get() = MR["action_download"]
        val action_bookmark: String get() = MR["action_bookmark"]
        val action_remove_bookmark: String get() = MR["action_remove_bookmark"]
        val action_delete: String get() = MR["action_delete"]
        val action_update_library: String get() = MR["action_update_library"]
        val action_enable_all: String get() = MR["action_enable_all"]
        val action_disable_all: String get() = MR["action_disable_all"]
        val action_edit: String get() = MR["action_edit"]
        val action_add: String get() = MR["action_add"]
        val action_update_category: String get() = MR["action_update_category"]
        val action_open_random_manga: String get() = MR["action_open_random_manga"]
        val action_add_category: String get() = MR["action_add_category"]
        val action_edit_categories: String get() = MR["action_edit_categories"]
        val action_rename_category: String get() = MR["action_rename_category"]
        val action_move_category: String get() = MR["action_move_category"]
        val delete_category_confirmation: String get() = MR["delete_category_confirmation"]
        val delete_category: String get() = MR["delete_category"]
        val action_edit_cover: String get() = MR["action_edit_cover"]
        val action_view_chapters: String get() = MR["action_view_chapters"]
        val action_pause: String get() = MR["action_pause"]
        val action_previous_chapter: String get() = MR["action_previous_chapter"]
        val action_next_chapter: String get() = MR["action_next_chapter"]
        val action_retry: String get() = MR["action_retry"]
        val action_remove: String get() = MR["action_remove"]
        val action_remove_everything: String get() = MR["action_remove_everything"]
        val action_start: String get() = MR["action_start"]
        val action_resume: String get() = MR["action_resume"]
        val action_open_in_browser: String get() = MR["action_open_in_browser"]
        val action_show_manga: String get() = MR["action_show_manga"]
        val action_copy_to_clipboard: String get() = MR["action_copy_to_clipboard"]
        val action_copy_link: String get() = MR["action_copy_link"]
        val action_open_in_web_view: String get() = MR["action_open_in_web_view"]
        val action_web_view: String get() = MR["action_web_view"]
        val action_migrate: String get() = MR["action_migrate"]
        val action_display_mode: String get() = MR["action_display_mode"]
        val action_display: String get() = MR["action_display"]
        val action_display_grid: String get() = MR["action_display_grid"]
        val action_display_comfortable_grid: String get() = MR["action_display_comfortable_grid"]
        val action_display_list: String get() = MR["action_display_list"]
        val action_display_cover_only_grid: String get() = MR["action_display_cover_only_grid"]
        val action_display_download_badge: String get() = MR["action_display_download_badge"]
        val action_display_unread_badge: String get() = MR["action_display_unread_badge"]
        val action_display_local_badge: String get() = MR["action_display_local_badge"]
        val action_display_language_badge: String get() = MR["action_display_language_badge"]
        val action_display_show_tabs: String get() = MR["action_display_show_tabs"]
        val action_display_show_number_of_items: String get() = MR["action_display_show_number_of_items"]
        val action_display_show_continue_reading_button: String get() = MR["action_display_show_continue_reading_button"]
        val action_disable: String get() = MR["action_disable"]
        val action_pin: String get() = MR["action_pin"]
        val action_unpin: String get() = MR["action_unpin"]
        val action_apply: String get() = MR["action_apply"]
        val action_cancel: String get() = MR["action_cancel"]
        val action_ok: String get() = MR["action_ok"]
        val action_cancel_all: String get() = MR["action_cancel_all"]
        val cancel_all_for_series: String get() = MR["cancel_all_for_series"]
        val action_sort: String get() = MR["action_sort"]
        val action_order_by_upload_date: String get() = MR["action_order_by_upload_date"]
        val action_order_by_chapter_number: String get() = MR["action_order_by_chapter_number"]
        val action_newest: String get() = MR["action_newest"]
        val action_oldest: String get() = MR["action_oldest"]
        val action_asc: String get() = MR["action_asc"]
        val action_desc: String get() = MR["action_desc"]
        val action_move_to_top: String get() = MR["action_move_to_top"]
        val action_move_to_top_all_for_series: String get() = MR["action_move_to_top_all_for_series"]
        val action_move_to_bottom: String get() = MR["action_move_to_bottom"]
        val action_move_to_bottom_all_for_series: String get() = MR["action_move_to_bottom_all_for_series"]
        val action_notes: String get() = MR["action_notes"]
        val action_edit_notes: String get() = MR["action_edit_notes"]
        val action_install: String get() = MR["action_install"]
        val action_share: String get() = MR["action_share"]
        val action_save: String get() = MR["action_save"]
        val action_reset: String get() = MR["action_reset"]
        val action_revert_to_default: String get() = MR["action_revert_to_default"]
        val action_close: String get() = MR["action_close"]
        val action_show_errors: String get() = MR["action_show_errors"]
        val action_restore: String get() = MR["action_restore"]
        val action_webview_back: String get() = MR["action_webview_back"]
        val action_webview_forward: String get() = MR["action_webview_forward"]
        val action_webview_refresh: String get() = MR["action_webview_refresh"]
        val action_webview_close_tab: String get() = MR["action_webview_close_tab"]
        val action_start_downloading_now: String get() = MR["action_start_downloading_now"]
        val action_not_now: String get() = MR["action_not_now"]
        val action_add_anyway: String get() = MR["action_add_anyway"]
        val action_bar_up_description: String get() = MR["action_bar_up_description"]
        val action_menu_overflow_description: String get() = MR["action_menu_overflow_description"]

        // Library Screen
        val library_title: String get() = MR["library_title"]
        val library_search_placeholder: String get() = MR["library_search_placeholder"]
        val library_search_tooltip: String get() = MR["library_search_tooltip"]
        val library_filter_tooltip: String get() = MR["library_filter_tooltip"]
        val library_overflow_tooltip: String get() = MR["library_overflow_tooltip"]
        val library_refresh_library: String get() = MR["library_refresh_library"]
        val library_import_file: String get() = MR["library_import_file"]
        val library_import_folder: String get() = MR["library_import_folder"]
        val library_sort_title: String get() = MR["library_sort_title"]
        val library_sort_unread: String get() = MR["library_sort_unread"]
        val library_sort_added: String get() = MR["library_sort_added"]
        val library_sort_downloaded: String get() = MR["library_sort_downloaded"]
        val library_sort_author: String get() = MR["library_sort_author"]
        val library_display_comfortable: String get() = MR["library_display_comfortable"]
        val library_display_compact_grid: String get() = MR["library_display_compact_grid"]
        val library_display_compact_list: String get() = MR["library_display_compact_list"]
        val library_filter_offline_only: String get() = MR["library_filter_offline_only"]
        val library_filter_unread_only: String get() = MR["library_filter_unread_only"]
        val library_filter_completed_only: String get() = MR["library_filter_completed_only"]
        val library_filter_genre: String get() = MR["library_filter_genre"]
        val library_filter_clear_active: String get() = MR["library_filter_clear_active"]
        val library_display_mode: String get() = MR["library_display_mode"]
        val library_sort_by: String get() = MR["library_sort_by"]
        val library_category_all: String get() = MR["library_category_all"]
        val library_category_reading: String get() = MR["library_category_reading"]
        val library_category_completed: String get() = MR["library_category_completed"]
        val library_category_plan_to_read: String get() = MR["library_category_plan_to_read"]
        val library_category_dropped: String get() = MR["library_category_dropped"]
        val library_category_on_hold: String get() = MR["library_category_on_hold"]
        val library_status_badge_all: String get() = MR["library_status_badge_all"]
        val library_status_badge_reading: String get() = MR["library_status_badge_reading"]
        val library_status_badge_completed: String get() = MR["library_status_badge_completed"]
        val library_status_badge_plan_to_read: String get() = MR["library_status_badge_plan_to_read"]
        val library_status_badge_dropped: String get() = MR["library_status_badge_dropped"]
        val library_status_badge_on_hold: String get() = MR["library_status_badge_on_hold"]
        val library_context_open_details: String get() = MR["library_context_open_details"]
        val library_context_move_reading: String get() = MR["library_context_move_reading"]
        val library_context_move_completed: String get() = MR["library_context_move_completed"]
        val library_context_move_plan_to_read: String get() = MR["library_context_move_plan_to_read"]
        val library_context_remove: String get() = MR["library_context_remove"]
        val library_confirm_remove: String get() = MR["library_confirm_remove"]
        val library_remove: String get() = MR["library_remove"]
        val library_importing: String get() = MR["library_importing"]
        val library_import_success: String get() = MR["library_import_success"]
        val library_import_failed: String get() = MR["library_import_failed"]
        val library_checking_updates: String get() = MR["library_checking_updates"]
        val library_synced: String get() = MR["library_synced"]
        val library_no_manga_to_check: String get() = MR["library_no_manga_to_check"]
        val library_empty: String get() = MR["library_empty"]
        val library_no_results: String get() = MR["library_no_results"]
        val library_loading: String get() = MR["library_loading"]

        // Details Screen
        val details_back: String get() = MR["details_back"]
        val details_download_all: String get() = MR["details_download_all"]
        val details_sync: String get() = MR["details_sync"]
        val details_category: String get() = MR["details_category"]
        val details_overflow_tooltip: String get() = MR["details_overflow_tooltip"]
        val details_chapters: String get() = MR["details_chapters"]
        val details_filter_all: String get() = MR["details_filter_all"]
        val details_filter_unread: String get() = MR["details_filter_unread"]
        val details_filter_downloaded: String get() = MR["details_filter_downloaded"]
        val details_sort_order: String get() = MR["details_sort_order"]
        val details_continue_reading: String get() = MR["details_continue_reading"]
        val details_read_from_start: String get() = MR["details_read_from_start"]
        val details_continue_chapter: String get() = MR["details_continue_chapter"]
        val details_add_to_library: String get() = MR["details_add_to_library"]
        val details_in_library: String get() = MR["details_in_library"]
        val details_track: String get() = MR["details_track"]
        val details_tracked_chapter: String get() = MR["details_tracked_chapter"]
        val details_mark_selected_read: String get() = MR["details_mark_selected_read"]
        val details_mark_selected_unread: String get() = MR["details_mark_selected_unread"]
        val details_export_selected: String get() = MR["details_export_selected"]
        val details_setup_tracking: String get() = MR["details_setup_tracking"]
        val details_remove_from_library: String get() = MR["details_remove_from_library"]
        val details_confirm_remove: String get() = MR["details_confirm_remove"]
        val details_source_local: String get() = MR["details_source_local"]
        val details_source_online: String get() = MR["details_source_online"]
        val details_status_badge_reading: String get() = MR["details_status_badge_reading"]
        val details_status_badge_completed: String get() = MR["details_status_badge_completed"]
        val details_status_badge_plan_to_read: String get() = MR["details_status_badge_plan_to_read"]
        val details_status_badge_dropped: String get() = MR["details_status_badge_dropped"]
        val details_status_badge_on_hold: String get() = MR["details_status_badge_on_hold"]
        val details_unread_count: String get() = MR["details_unread_count"]
        val details_downloaded_count: String get() = MR["details_downloaded_count"]
        val details_chapter_count: String get() = MR["details_chapter_count"]
        val details_chapter_filtered: String get() = MR["details_chapter_filtered"]
        val details_chapter_unread: String get() = MR["details_chapter_unread"]
        val details_chapter_up_to_date: String get() = MR["details_chapter_up_to_date"]
        val details_context_read_now: String get() = MR["details_context_read_now"]
        val details_context_download: String get() = MR["details_context_download"]
        val details_context_export: String get() = MR["details_context_export"]
        val details_context_mark_read: String get() = MR["details_context_mark_read"]
        val details_context_mark_unread: String get() = MR["details_context_mark_unread"]
        val details_chapter_status_downloaded: String get() = MR["details_chapter_status_downloaded"]
        val details_chapter_status_downloading: String get() = MR["details_chapter_status_downloading"]
        val details_chapter_status_read: String get() = MR["details_chapter_status_read"]
        val details_chapter_status_partial: String get() = MR["details_chapter_status_partial"]
        val details_chapter_action_read: String get() = MR["details_chapter_action_read"]
        val details_empty: String get() = MR["details_empty"]
        val details_chapter_not_found: String get() = MR["details_chapter_not_found"]
        val details_refreshing: String get() = MR["details_refreshing"]
        val details_updated: String get() = MR["details_updated"]
        val details_refresh_failed: String get() = MR["details_refresh_failed"]
        val details_download_preparing: String get() = MR["details_download_preparing"]
        val details_download_progress: String get() = MR["details_download_progress"]
        val details_download_complete: String get() = MR["details_download_complete"]
        val details_download_failed: String get() = MR["details_download_failed"]
        val details_mark_complete: String get() = MR["details_mark_complete"]
        val details_export_dialog: String get() = MR["details_export_dialog"]
        val details_tracker_dialog: String get() = MR["details_tracker_dialog"]
        val details_no_chapter_to_export: String get() = MR["details_no_chapter_to_export"]
        val details_open_source_catalog: String get() = MR["details_open_source_catalog"]
        val details_tracker_tokens_title: String get() = MR["details_tracker_tokens_title"]
        val details_tracker_tokens_prompt: String get() = MR["details_tracker_tokens_prompt"]
        val details_anilist_token: String get() = MR["details_anilist_token"]
        val details_mal_token: String get() = MR["details_mal_token"]
        val details_tracker_tokens_saved: String get() = MR["details_tracker_tokens_saved"]
        val details_category_updated: String get() = MR["details_category_updated"]
        val details_favorite_added: String get() = MR["details_favorite_added"]
        val details_favorite_removed: String get() = MR["details_favorite_removed"]
        val details_removed: String get() = MR["details_removed"]

        // Reader Window
        val reader_window_title: String get() = MR["reader_window_title"]
        val reader_mode_single: String get() = MR["reader_mode_single"]
        val reader_mode_double_rtl: String get() = MR["reader_mode_double_rtl"]
        val reader_mode_double_ltr: String get() = MR["reader_mode_double_ltr"]
        val reader_mode_webtoon: String get() = MR["reader_mode_webtoon"]
        val reader_mode_continuous_vertical: String get() = MR["reader_mode_continuous_vertical"]
        val reader_fit_window: String get() = MR["reader_fit_window"]
        val reader_fit_width: String get() = MR["reader_fit_width"]
        val reader_fit_original: String get() = MR["reader_fit_original"]
        val reader_quality_fast: String get() = MR["reader_quality_fast"]
        val reader_quality_bilinear: String get() = MR["reader_quality_bilinear"]
        val reader_quality_nitido: String get() = MR["reader_quality_nitido"]
        val reader_color_filter_none: String get() = MR["reader_color_filter_none"]
        val reader_color_filter_grayscale: String get() = MR["reader_color_filter_grayscale"]
        val reader_color_filter_night_inverted: String get() = MR["reader_color_filter_night_inverted"]
        val reader_color_filter_sepia: String get() = MR["reader_color_filter_sepia"]
        val reader_prev_chapter: String get() = MR["reader_prev_chapter"]
        val reader_next_chapter: String get() = MR["reader_next_chapter"]
        val reader_auto_scroll: String get() = MR["reader_auto_scroll"]
        val reader_auto_scroll_running: String get() = MR["reader_auto_scroll_running"]
        val reader_zoom_out: String get() = MR["reader_zoom_out"]
        val reader_zoom_reset: String get() = MR["reader_zoom_reset"]
        val reader_zoom_in: String get() = MR["reader_zoom_in"]
        val reader_fullscreen: String get() = MR["reader_fullscreen"]
        val reader_hide_hud: String get() = MR["reader_hide_hud"]
        val reader_margin: String get() = MR["reader_margin"]
        val reader_split_enabled: String get() = MR["reader_split_enabled"]
        val reader_split_disabled: String get() = MR["reader_split_disabled"]
        val reader_scroll_state_running: String get() = MR["reader_scroll_state_running"]
        val reader_scroll_state_paused_hover: String get() = MR["reader_scroll_state_paused_hover"]
        val reader_scroll_state_paused_manual: String get() = MR["reader_scroll_state_paused_manual"]
        val reader_scroll_state_stopped: String get() = MR["reader_scroll_state_stopped"]
        val reader_speed_hint: String get() = MR["reader_speed_hint"]
        val reader_page_label: String get() = MR["reader_page_label"]
        val reader_pages_label: String get() = MR["reader_pages_label"]
        val reader_page_split_label: String get() = MR["reader_page_split_label"]
        val reader_loading: String get() = MR["reader_loading"]
        val reader_decode_error: String get() = MR["reader_decode_error"]
        val reader_no_images: String get() = MR["reader_no_images"]
        val reader_zoom_range: String get() = MR["reader_zoom_range"]
        val reader_cache_entries: String get() = MR["reader_cache_entries"]
        val reader_memory_limit: String get() = MR["reader_memory_limit"]
        val reader_used_memory: String get() = MR["reader_used_memory"]
        val reader_auto_scroll_speed_base: String get() = MR["reader_auto_scroll_speed_base"]
        val reader_auto_scroll_speed_level: String get() = MR["reader_auto_scroll_speed_level"]

        // Search Screens
        val search_title: String get() = MR["search_title"]
        val search_placeholder: String get() = MR["search_placeholder"]
        val search_source_all: String get() = MR["search_source_all"]
        val search_button_popular: String get() = MR["search_button_popular"]
        val search_button_search: String get() = MR["search_button_search"]
        val search_back_to_sources: String get() = MR["search_back_to_sources"]
        val search_loading_popular: String get() = MR["search_loading_popular"]
        val search_showing_popular: String get() = MR["search_showing_popular"]
        val search_searching: String get() = MR["search_searching"]
        val search_results: String get() = MR["search_results"]
        val search_no_sources: String get() = MR["search_no_sources"]
        val search_error: String get() = MR["search_error"]
        val search_failed: String get() = MR["search_failed"]
        val search_open_failed: String get() = MR["search_open_failed"]

        // History Screen
        val history_title: String get() = MR["history_title"]
        val history_empty_title: String get() = MR["history_empty_title"]
        val history_empty_subtitle: String get() = MR["history_empty_subtitle"]
        val history_refresh: String get() = MR["history_refresh"]
        val history_clear_all: String get() = MR["history_clear_all"]
        val history_confirm_clear: String get() = MR["history_confirm_clear"]
        val history_cleared: String get() = MR["history_cleared"]
        val history_today: String get() = MR["history_today"]
        val history_yesterday: String get() = MR["history_yesterday"]
        val history_date_format: String get() = MR["history_date_format"]
        val history_time_format: String get() = MR["history_time_format"]
        val history_favorite_added: String get() = MR["history_favorite_added"]
        val history_favorite_removed: String get() = MR["history_favorite_removed"]
        val history_entry_removed: String get() = MR["history_entry_removed"]

        // Extensions Screen
        val extensions_title: String get() = MR["extensions_title"]
        val extensions_beta_generator: String get() = MR["extensions_beta_generator"]
        val extensions_beta_description: String get() = MR["extensions_beta_description"]
        val extensions_url_placeholder: String get() = MR["extensions_url_placeholder"]
        val extensions_generate_button: String get() = MR["extensions_generate_button"]
        val extensions_generating: String get() = MR["extensions_generating"]
        val extensions_community_catalog: String get() = MR["extensions_community_catalog"]
        val extensions_generated_title: String get() = MR["extensions_generated_title"]
        val extensions_generated_message: String get() = MR["extensions_generated_message"]
        val extensions_generate_failed: String get() = MR["extensions_generate_failed"]
        val extensions_active: String get() = MR["extensions_active"]
        val extensions_inactive: String get() = MR["extensions_inactive"]
        val extensions_nsfw: String get() = MR["extensions_nsfw"]
        val extensions_toggle_enable: String get() = MR["extensions_toggle_enable"]
        val extensions_toggle_disable: String get() = MR["extensions_toggle_disable"]
        val extensions_remove: String get() = MR["extensions_remove"]
        val extensions_confirm_remove: String get() = MR["extensions_confirm_remove"]
        val extensions_activated: String get() = MR["extensions_activated"]
        val extensions_deactivated: String get() = MR["extensions_deactivated"]
        val extensions_removed: String get() = MR["extensions_removed"]
        val extensions_active_count: String get() = MR["extensions_active_count"]
        val extensions_loading_disabled: String get() = MR["extensions_loading_disabled"]

        // Sources List Screen
        val sources_title: String get() = MR["sources_title"]
        val sources_search_placeholder: String get() = MR["sources_search_placeholder"]
        val sources_section_pinned: String get() = MR["sources_section_pinned"]
        val sources_section_last_used: String get() = MR["sources_section_last_used"]
        val sources_section_other: String get() = MR["sources_section_other"]
        val sources_section_local: String get() = MR["sources_section_local"]
        val sources_section_pt_br: String get() = MR["sources_section_pt_br"]
        val sources_section_en_global: String get() = MR["sources_section_en_global"]
        val sources_local_title: String get() = MR["sources_local_title"]
        val sources_local_subtitle: String get() = MR["sources_local_subtitle"]
        val sources_recents_button: String get() = MR["sources_recents_button"]
        val sources_pin_tooltip_pin: String get() = MR["sources_pin_tooltip_pin"]
        val sources_pin_tooltip_unpin: String get() = MR["sources_pin_tooltip_unpin"]
        val sources_no_pinned: String get() = MR["sources_no_pinned"]
        val sources_pinned: String get() = MR["sources_pinned"]

        // Settings Screen
        val settings_general: String get() = MR["settings_general"]
        val settings_appearance: String get() = MR["settings_appearance"]
        val settings_reader: String get() = MR["settings_reader"]
        val settings_performance: String get() = MR["settings_performance"]
        val settings_storage: String get() = MR["settings_storage"]
        val settings_about: String get() = MR["settings_about"]
        val settings_privacy_history: String get() = MR["settings_privacy_history"]
        val settings_execution_network: String get() = MR["settings_execution_network"]
        val settings_sync_tracking: String get() = MR["settings_sync_tracking"]
        val settings_theme_ui: String get() = MR["settings_theme_ui"]
        val settings_navigation_sidebar: String get() = MR["settings_navigation_sidebar"]
        val settings_pagination_behavior: String get() = MR["settings_pagination_behavior"]
        val settings_double_page_spreads: String get() = MR["settings_double_page_spreads"]
        val settings_keyboard_shortcuts: String get() = MR["settings_keyboard_shortcuts"]
        val settings_ram_management: String get() = MR["settings_ram_management"]
        val settings_realtime_rendering: String get() = MR["settings_realtime_rendering"]
        val settings_download_postprocessing: String get() = MR["settings_download_postprocessing"]
        val settings_backup: String get() = MR["settings_backup"]
        val settings_system_directories: String get() = MR["settings_system_directories"]
        val settings_download_location: String get() = MR["settings_download_location"]
        val settings_database_location: String get() = MR["settings_database_location"]
        val settings_sources_location: String get() = MR["settings_sources_location"]
        val settings_theme_preset: String get() = MR["settings_theme_preset"]
        val settings_sidebar_style: String get() = MR["settings_sidebar_style"]
        val settings_default_read_mode: String get() = MR["settings_default_read_mode"]
        val settings_page_fitting: String get() = MR["settings_page_fitting"]
        val settings_auto_split_wide_pages: String get() = MR["settings_auto_split_wide_pages"]
        val settings_split_reading_order: String get() = MR["settings_split_reading_order"]
        val settings_render_quality: String get() = MR["settings_render_quality"]
        val settings_visual_test_lab: String get() = MR["settings_visual_test_lab"]
        val settings_install_enhance_mode: String get() = MR["settings_install_enhance_mode"]
        val settings_install_output_format: String get() = MR["settings_install_output_format"]
        val settings_ram_limit: String get() = MR["settings_ram_limit"]
        val settings_memory_monitor: String get() = MR["settings_memory_monitor"]
        val settings_clean_ram: String get() = MR["settings_clean_ram"]
        val settings_export_backup: String get() = MR["settings_export_backup"]
        val settings_import_backup: String get() = MR["settings_import_backup"]
        val settings_open_folder: String get() = MR["settings_open_folder"]
        val settings_language: String get() = MR["settings_language"]
        val settings_language_restart_required: String get() = MR["settings_language_restart_required"]
    }

    // Date/Number formatting utilities
    object format {
        /**
         * Returns a localized date formatter with MEDIUM style.
         */
        fun dateFormatter(): java.time.format.DateTimeFormatter {
            return java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(currentLocale)
        }

        /**
         * Returns a localized time formatter with SHORT style.
         */
        fun timeFormatter(): java.time.format.DateTimeFormatter {
            return java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
                .withLocale(currentLocale)
        }

        /**
         * Returns a localized date-time formatter with MEDIUM date and SHORT time.
         */
        fun dateTimeFormatter(): java.time.format.DateTimeFormatter {
            return java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
                .withLocale(currentLocale)
        }

        /**
         * Returns a localized number formatter.
         */
        fun numberFormatter(): java.text.NumberFormat {
            return java.text.NumberFormat.getInstance(currentLocale)
        }

        /**
         * Returns a localized percent formatter.
         */
        fun percentFormatter(): java.text.NumberFormat {
            return java.text.NumberFormat.getPercentInstance(currentLocale)
        }

        /**
         * Returns a localized currency formatter.
         */
        fun currencyFormatter(): java.text.NumberFormat {
            return java.text.NumberFormat.getCurrencyInstance(currentLocale)
        }
    }

    // RTL support preparation
    object orientation {
        /**
         * Checks if the current locale is RTL (right-to-left).
         * Currently supports Arabic (ar), Hebrew (he), Persian (fa), Urdu (ur).
         */
        val isRTL: Boolean
            get() = currentLocale.language in setOf("ar", "he", "fa", "ur")

        /**
         * Returns the appropriate ComponentOrientation for the current locale.
         * For use with Swing components: component.applyComponentOrientation(MR.orientation.componentOrientation)
         */
        val componentOrientation: java.awt.ComponentOrientation
            get() = if (isRTL) {
                java.awt.ComponentOrientation.getOrientation(currentLocale)
            } else {
                java.awt.ComponentOrientation.LEFT_TO_RIGHT
            }

        /**
         * Applies the correct component orientation to a Swing component and all its children.
         */
        fun applyTo(component: java.awt.Component) {
            component.applyComponentOrientation(componentOrientation)
        }
    }
}
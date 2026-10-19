package app.ipainstaller.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * App strings in Russian and English. Kept in code (not Android resources) so the installer
 * core can use them on a plain JVM too, and so the language can be switched from the app menu.
 */
public final class I18n {
    public static final String RU = "ru", EN = "en";

    private static volatile String lang = Locale.getDefault().getLanguage().equals(RU) ? RU : EN;
    private static final Map<String, String[]> S = new HashMap<String, String[]>();   // key -> {ru, en}

    private I18n() {}

    public static String lang() {
        return lang;
    }

    public static void setLang(String l) {
        lang = RU.equals(l) ? RU : EN;
    }

    /** Looks up a string for the current language and applies String.format arguments. */
    public static String t(String key, Object... args) {
        String[] v = S.get(key);
        if (v == null) return key;
        String s = RU.equals(lang) ? v[0] : v[1];
        return args.length == 0 ? s : String.format(Locale.US, s, args);
    }

    private static void put(String key, String ru, String en) {
        S.put(key, new String[]{ru, en});
    }

    static {
        // ---- installer flow
        put("connecting", "Подключение к iPhone…", "Connecting to iPhone…");
        put("cancelled", "Отменено", "Cancelled");
        put("installed_ok", "%s: установлено ✔", "%s: installed ✔");
        put("install_error", "%s: ОШИБКА — %s", "%s: ERROR — %s");
        put("paired_already", "Сопряжение уже есть, сессия открыта", "Already paired, session started");
        put("pair_rejected", "Сохранённое сопряжение не принято (%s), сопрягаю заново", "Saved pairing was rejected (%s), pairing again");
        put("generating_keys", "Создание ключей для сопряжения…", "Generating pairing keys…");
        put("paired", "Сопряжение выполнено", "Paired");
        put("tap_trust", "На iPhone нажмите «Доверять» и введите код-пароль", "On the iPhone tap “Trust” and enter the passcode");
        put("unlock", "Разблокируйте iPhone, чтобы появился запрос «Доверять этому компьютеру?»",
                "Unlock the iPhone so the “Trust This Computer?” prompt appears");
        put("denied", "На iPhone выбрано «Не доверять». Отключите и снова подключите кабель, затем нажмите «Доверять».",
                "“Don’t Trust” was chosen on the iPhone. Unplug and reconnect the cable, then tap “Trust”.");
        put("trust_timeout", "Не дождался подтверждения «Доверять» на iPhone", "Timed out waiting for “Trust” on the iPhone");
        put("copying", "Копирование %s", "Copying %s");
        put("copying_progress", "Копирование %s — %s из %s", "Copying %s — %s of %s");
        put("copying_progress_nototal", "Копирование %s — %s", "Copying %s — %s");
        put("copied", "Скопировано %s за %.1f с (%s/с)", "Copied %s in %.1f s (%s/s)");
        put("installing", "Установка %s…", "Installing %s…");
        put("installing_status", "Установка %s: %s", "Installing %s: %s");
        put("unit_b", "Б", "B");
        put("unit_kb", "КБ", "KB");
        put("unit_mb", "МБ", "MB");
        put("unit_gb", "ГБ", "GB");

        // ---- USB / protocol
        put("usb_open_failed", "Не удалось открыть USB-устройство (нет разрешения?)", "Could not open the USB device (no permission?)");
        put("usb_no_mux", "На iPhone не найден интерфейс usbmux", "No usbmux interface found on the iPhone");
        put("usb_switch_config", "USB: переключаю конфигурацию %d → %d", "USB: switching configuration %d → %d");
        put("usb_switch_failed", "Не удалось переключить USB-конфигурацию iPhone", "Could not switch the iPhone's USB configuration");
        put("usb_no_endpoints", "У интерфейса usbmux нет bulk-endpoint'ов", "The usbmux interface has no bulk endpoints");
        put("usb_claim_failed", "Не удалось захватить интерфейс usbmux", "Could not claim the usbmux interface");
        put("usb_info", "USB: конфигурация %d, интерфейс %d, endpoints %s/%s, wMaxPacketSize %d",
                "USB: configuration %d, interface %d, endpoints %s/%s, wMaxPacketSize %d");
        put("usb_read_error", "Ошибка чтения USB (кабель отключён?)", "USB read error (cable unplugged?)");
        put("usb_write_error", "Ошибка записи USB (кабель отключён?)", "USB write error (cable unplugged?)");
        put("mux_version", "usbmux: протокол v%d", "usbmux: protocol v%d");
        put("mux_unknown", "usbmux: неизвестный пакет протокола %d", "usbmux: unknown packet protocol %d");
        put("afc_1", "неизвестная ошибка (1)", "unknown error (1)");
        put("afc_7", "неверный аргумент (7)", "invalid argument (7)");
        put("afc_8", "объект не найден (8)", "object not found (8)");
        put("afc_9", "это каталог (9)", "is a directory (9)");
        put("afc_10", "нет доступа (10)", "permission denied (10)");
        put("afc_15", "недостаточно ресурсов (15)", "out of resources (15)");
        put("afc_16", "объект уже существует (16)", "object exists (16)");
        put("afc_18", "нет места на iPhone (18)", "no space left on iPhone (18)");
        put("afc_code", "код %d", "code %d");

        // ---- UI
        put("title", "IPA Installer by @altyn", "IPA Installer by @altyn");
        put("language", "Язык", "Language");
        put("lang_ru", "Русский", "Русский");
        put("lang_en", "English", "English");
        put("no_iphone", "iPhone не подключён", "iPhone not connected");
        put("connected", "Подключён: %s", "Connected: %s");
        put("needs_permission", " (нужно разрешение)", " (permission needed)");
        put("pick", "Выбрать IPA-файлы", "Choose IPA files");
        put("install", "Установить на iPhone", "Install on iPhone");
        put("cancel", "Отменить", "Cancel");
        put("cancelling", "Отмена…", "Cancelling…");
        put("no_files", "Файлы не выбраны", "No files selected");
        put("log", "Журнал", "Log");
        put("ready", "Готово. Подключите iPhone кабелем (через OTG), выберите IPA-файлы и нажмите «Установить».",
                "Ready. Connect the iPhone with a cable (via OTG), choose IPA files and tap “Install”.");
        put("usb_granted", "Доступ к USB-устройству разрешён", "USB access granted");
        put("usb_denied", "Доступ к USB-устройству не разрешён", "USB access denied");
        put("iphone_detached", "iPhone отключён", "iPhone disconnected");
        put("iphone_attached", "iPhone подключён", "iPhone connected");
        put("file_info_failed", "Не удалось прочитать сведения о файле: %s", "Could not read file info: %s");
        put("not_ipa", "Внимание: %s не похож на .ipa", "Warning: %s does not look like an .ipa");
        put("choose_files_first", "Сначала выберите IPA-файлы", "Choose IPA files first");
        put("iphone_not_found", "iPhone не найден. Проверьте кабель и OTG-переходник, разблокируйте iPhone.",
                "iPhone not found. Check the cable and OTG adapter, and unlock the iPhone.");
        put("permission_requested", "Запрошен доступ к iPhone — подтвердите в диалоге", "Requested access to the iPhone, confirm in the dialog");
        put("done_all", "Готово: установлено %d из %d", "Done: installed %d of %d");
        put("done_partial", "Установлено %d из %d — подробности в журнале", "Installed %d of %d, see the log for details");
        put("error", "Ошибка: %s", "Error: %s");
    }
}

<p align="center"><img src="docs/logo.png" width="160" alt="ALTYN STORE"></p>

# IPA Installer by @altyn

**Русский** · [English](#english)

Приложение для Android, которое устанавливает готовые `.ipa` с памяти телефона на iPhone по USB-кабелю, без компьютера и без iTunes. Android работает как USB-хост и сам делает то, что на компьютере делают `usbmuxd` и `ideviceinstaller`. После установки иконка приложения появляется на iPhone.

Подписывать IPA приложение не умеет и не должно: файл должен быть заранее подписан так, чтобы iPhone его принял.

**Скачать:** [dist/ipa-installer-debug.apk](dist/ipa-installer-debug.apk)

## Возможности

* Установка одного или нескольких `.ipa` за раз, с прогрессом копирования и установки.
* Сопряжение с iPhone через стандартный запрос «Доверять этому компьютеру?». Сопряжение запоминается.
* Файлы можно выбрать в приложении или отправить в него через «Поделиться» из файлового менеджера.
* Интерфейс на русском и английском, язык переключается в меню ⋮.
* Подробный журнал на экране для диагностики.
* Без нативных библиотек и сторонних зависимостей, приложение весит меньше 100 КБ.

## Требования

* Android 8.0+ с поддержкой USB OTG. Целевое устройство: Samsung Galaxy S10 (Android 12).
* iPhone с iOS 18 или новее (протокол тот же и на более старых версиях).
* Кабель:
  * iPhone с Lightning: кабель USB-C → Lightning прямо в телефон Android;
  * iPhone 15 и новее (USB-C): OTG-переходник USB-C → USB-A и кабель USB-A → USB-C, чтобы хостом гарантированно стал Android.
* IPA подписана ad-hoc/development-профилем с UDID этого iPhone (UDID показывается в журнале при подключении) или enterprise-сертификатом. Для development-подписи на iPhone нужно включить «Режим разработчика».

## Как пользоваться

1. Установите APK на Android.
2. Подключите iPhone кабелем и разблокируйте его. Разрешите Android открыть IPA Installer для этого устройства.
3. Нажмите «Выбрать IPA-файлы».
4. Нажмите «Установить на iPhone». В первый раз нажмите на iPhone «Доверять» и введите код-пароль.
5. Дождитесь «установлено ✔» и найдите иконку на iPhone.

## Как это устроено

| Шаг | Файл | Что делает |
|---|---|---|
| USB | `usbmux/AndroidUsbPipe.java` | находит интерфейс usbmux (класс FF/FE/02), при необходимости переключает USB-конфигурацию iPhone |
| usbmux | `usbmux/UsbMux.java`, `MuxConnection.java` | протокол usbmux v2, как в `usbmuxd`: соединения к портам iPhone с окнами и подтверждениями |
| сопряжение | `lockdown/PairingCrypto.java`, `LockdownClient.java` | RSA-2048 сертификаты, как в `libimobiledevice`, запрос `Pair`, сессия lockdownd с TLS |
| загрузка | `services/AfcClient.java` | AFC: копирует IPA в `PublicStaging/` |
| установка | `services/InstallationProxy.java` | `installation_proxy` → `Install` |
| сценарий | `IpaInstaller.java` | весь процесс, как `ideviceinstaller install app.ipa` |
| интерфейс | `MainActivity.java`, `util/I18n.java` | экран, заставка, выбор языка |

## Сборка

Сборка идёт скриптом без Gradle:

```
TOOLS=/путь/к/инструментам bash build.sh     # → app/build/ipa-installer-debug.apk
BCPROV=/путь/к/bcprov.jar bash run-tests.sh   # тесты на JVM
```

В `$TOOLS` нужны: `android33.jar` (android.jar API 33), `aapt2` (Linux x86_64, например `prebuilt/linux/aapt2_64` из apktool 2.10), `dx.jar` (`com.jakewharton.android.repackaged:dalvik-dx:16.0.1`), `apksig.jar` (`com.android.tools.build:apksig:2.3.0`). Для тестов нужен `bcprov-jdk18on`. Также нужны JDK 17+ и python3. Код должен оставаться на Java 8 без лямбд, потому что dx их не преобразует.

Тесты (`run-tests.sh`) прогоняют весь процесс против симулятора iPhone (`app/src/test/.../FakeIphone.java`). Симулятор реализует usbmux, lockdownd с запросом «Доверять», TLS, AFC и installation_proxy.

## Статус

Протоколы реализованы по `usbmuxd`, `libimobiledevice` и `ideviceinstaller`, полный сценарий проходит тест против симулятора iPhone. Проверка на реальных устройствах идёт сейчас. Если что-то не работает, создайте issue и приложите журнал из приложения.

Ключ подписи (`tools/debug.p12`) в репозиторий не входит. Без него `build.sh` создаст новый, но такую сборку нельзя будет поставить поверх версии, подписанной старым ключом.

---

## English

An Android app that installs ready-made `.ipa` files from the phone's storage onto an iPhone over a USB cable, with no computer and no iTunes. Android acts as the USB host and does the job of `usbmuxd` + `ideviceinstaller` itself. After installation the app icon appears on the iPhone.

The app does not sign IPA files: each file must already be signed so that the iPhone accepts it.

**Download:** [dist/ipa-installer-debug.apk](dist/ipa-installer-debug.apk)

### Features

* Installs one or several `.ipa` files in one go, with copy and install progress.
* Pairs with the iPhone through the standard “Trust This Computer?” prompt, and remembers the pairing.
* Pick files in the app, or share them to it from a file manager.
* Russian and English UI, switchable from the ⋮ menu.
* On-screen log for troubleshooting.
* No native libraries or third-party dependencies; the APK is under 100 KB.

### Requirements

* Android 8.0+ with USB OTG. Target device: Samsung Galaxy S10 (Android 12).
* iPhone on iOS 18 or newer (older versions use the same protocol).
* Cable: USB-C to Lightning for Lightning iPhones. For USB-C iPhones (15 and newer), use a USB-C to USB-A OTG adapter plus a USB-A to USB-C cable, so that Android is always the host.
* The IPA must be signed with an ad-hoc/development profile that includes this iPhone's UDID (shown in the log when you connect) or with an enterprise certificate. Development-signed apps need Developer Mode turned on in iOS.

### Usage

1. Install the APK on Android.
2. Connect and unlock the iPhone, then allow Android to open IPA Installer for it.
3. Tap “Choose IPA files”.
4. Tap “Install on iPhone”. The first time, tap “Trust” on the iPhone and enter the passcode.
5. Wait for “installed ✔” and find the icon on the iPhone.

### How it works and building

See the Russian section above: it has the file-by-file overview and build commands, which are the same in both languages.

### Status

The protocol code follows `usbmuxd`, `libimobiledevice` and `ideviceinstaller`, and it passes an end-to-end test against a simulated iPhone. Testing on real hardware is in progress. If something fails, please open an issue with the in-app log.

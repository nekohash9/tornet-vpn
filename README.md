# TorNet

**Tor для Android с автоматической сменой IP, мостами от блокировок и режимом VPN.**
Мобильная версия идеи [tornet](https://github.com/ByteBreach/tornet): Tor работает прямо в
приложении — Orbot и root не нужны.

**[⬇ Скачать последнюю версию](https://github.com/nekohash9/tornet-vpn/releases/latest)**

> Какой файл брать: `…-arm64-v8a.apk` — для почти всех современных телефонов,
> `…-universal.apk` — если не уверены. Android 7.0 и новее.

## Возможности

- **Смена IP по таймеру** — фиксированный или случайный интервал («от–до»), заданное число раз
  или бесконечно. Кнопка «Новый IP» в приложении и в уведомлении.
- **Режим VPN** — весь трафик телефона идёт через Tor, включая DNS. Без настройки прокси в
  каждом приложении. Поддерживается «Постоянный VPN» Android.
- **Мосты от блокировок** — если подключение застревает на 5–10%:
  - **Авто** сам перебирает способы, пока один не сработает, и запоминает удачный;
  - obfs4, Snowflake, dnstt (через DNS-over-HTTPS), meek, свои мосты;
  - запрос свежих мостов для вашей страны у Tor Project (Moat) — работает даже при
    заблокированном сайте Tor.
- **Telegram в один тап** — открывает Telegram с готовым прокси.
- **Раздача прокси в Wi-Fi и точку доступа** — компьютер или другой телефон в той же сети
  пойдут через Tor этого телефона (ссылка `t.me/socks` для Telegram Desktop).
- **Локальные прокси** SOCKS5 `127.0.0.1:9050` и HTTP `127.0.0.1:8118` для любых приложений.
- Журнал событий: подключение, каждая смена IP, ошибки.

## Как пользоваться

1. Установите APK (разрешите установку из неизвестных источников).
2. Нажмите **Запустить**. Первый запуск может занять до пары минут.
3. Для всего трафика — включите **Режим VPN** и разрешите VPN в системном окне.
4. Не подключается — оставьте **Авто** в разделе «Подключение к Tor» или вставьте мосты из
   Telegram-бота [@GetBridgesBot](https://t.me/GetBridgesBot).

## Важно знать

- **Tor в режиме VPN ≠ Tor Browser.** IP скрыт, но обычный браузер выдаёт вас по cookie,
  аккаунтам и «отпечатку» устройства. Для анонимного сёрфинга используйте Tor Browser.
- **UDP через Tor не работает:** звонки в мессенджерах, часть игр, QUIC. Большинство приложений
  сами переключаются на TCP.
- Некоторые сайты и банки блокируют вход из сети Tor.
- Смена IP применяется к **новым** соединениям: уже открытые продолжают идти через старую цепочку.

## Конфиденциальность

В приложении нет аналитики, рекламы и трекеров. Оно подключается только:

- к сети Tor (напрямую или через выбранные мосты);
- к `check.torproject.org` и `api.ipify.org` — **через Tor**, чтобы показать ваш текущий внешний IP;
- к Moat (API мостов Tor Project) — только при запросе мостов, через meek/dnstt-туннель.

## Разрешения

| Разрешение | Зачем |
| --- | --- |
| Интернет | подключение к Tor |
| VPN (`BIND_VPN_SERVICE`) | режим VPN — запрашивается отдельно, только если вы его включите |
| Уведомления, работа в фоне | Tor продолжает работать при свёрнутом приложении; уведомление с IP и кнопками |

## Сборка из исходников

Нужны JDK 17, Android SDK и NDK.

```sh
git clone --recursive https://github.com/nekohash9/tornet-vpn
cd tornet-vpn
./gradlew assembleRelease
# app/build/outputs/apk/release/
```

Без переменных `TORNET_KEYSTORE*` APK подписывается debug-ключом — для личного использования.

## Публикация новой версии

Релиз собирается GitHub Actions по тегу и сам появляется в Releases:

```sh
git tag v1.4.0
git push origin v1.4.0
```

Перед первым релизом добавьте секреты репозитория (Settings → Secrets and variables → Actions):
`TORNET_KEYSTORE_BASE64` (keystore в base64), `TORNET_KEYSTORE_PASSWORD`, `TORNET_KEY_ALIAS`,
`TORNET_KEY_PASSWORD`. Ключ должен быть всегда один и тот же — иначе обновление не встанет поверх.

## Устройство

- `TorNetService` — foreground-сервис: встроенный Tor ([tor-android](https://github.com/guardianproject/tor-android)),
  подбор способа подключения, смена IP (`SIGNAL NEWNYM`), проверка IP через HTTP-туннель Tor.
- `PluggableTransports`, `Bridges`, `Moat` — мосты через [IPtProxy](https://github.com/tladesignz/IPtProxy)
  (lyrebird, Snowflake, dnstt) и запрос мостов у Tor Project.
- `TorVpnService` — `VpnService` + [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  (tun2socks, git-сабмодуль), DNS через mapdns на стороне Tor.
- `MainActivity` — интерфейс.

## Лицензии

Код приложения — [MIT](LICENSE). Используемые компоненты: Tor (BSD-3-Clause),
tor-android и jtorctl (BSD-3-Clause), IPtProxy (MIT) с lyrebird и Snowflake (BSD),
hev-socks5-tunnel (MIT).

---

**English:** TorNet is an Android app that runs Tor in-app, rotates your exit IP on a timer,
routes all traffic through Tor in VPN mode, and gets around censorship with obfs4, Snowflake,
dnstt, meek or custom bridges (with automatic fallback and Moat). UI is in Russian.
[Download the latest release](https://github.com/nekohash9/tornet-vpn/releases/latest).

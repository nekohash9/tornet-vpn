"""Источник всех строк интерфейса: python tools/gen_strings.py app/src/main/res

Генерирует values/strings.xml (английский, по умолчанию) и values-ru/strings.xml.
"""
import sys
from xml.sax.saxutils import escape
S = [
("app_name","TorNet","TorNet"),
("subtitle","Automatic IP rotation over Tor","Автоматическая смена IP через Tor"),
("notification_channel","Tor service","Работа Tor"),
("status_off","Tor is off","Tor выключен"),
("status_starting","Connecting to Tor: %1$d%% %2$s","Подключение к Tor: %1$d%% %2$s"),
("status_on","Tor is connected","Tor подключён"),
("status_stopping","Stopping…","Остановка…"),
("current_ip","Exit IP","Внешний IP"),
("dash","—","—"),
("changes_of","IP changes: %1$d of %2$d","Смен IP: %1$d из %2$d"),
("changes_inf","IP changes: %1$d (unlimited)","Смен IP: %1$d (без ограничения)"),
("next_change","Next change in %1$d s","Следующая смена через %1$d с"),
("start","Start","Запустить"),
("stop","Stop","Остановить"),
("new_ip","New IP","Новый IP"),
("settings","IP rotation","Смена IP"),
("interval_from","Interval from, s","Интервал от, с"),
("interval_to","to, s","до, с"),
("count_hint","How many times","Сколько раз сменить"),
("count_helper","0 — forever. The interval is picked randomly within the range, at least 10 s.","0 — бесконечно. Интервал выбирается случайно в заданных пределах, минимум 10 с."),
("apply","Apply","Применить"),
("vpn_mode","VPN mode — all traffic through Tor","Режим VPN — весь трафик через Tor"),
("vpn_help","All apps go through Tor without proxy setup, DNS included. UDP (calls, games, QUIC) doesn't work over Tor — apps usually fall back to TCP.","Все приложения пойдут через Tor без настройки прокси, DNS тоже. UDP (звонки, игры, QUIC) через Tor не работает — приложения обычно переключаются на TCP."),
("vpn_active","VPN on: all traffic through Tor","VPN включён: весь трафик через Tor"),
("bridges_title","Connecting to Tor","Подключение к Tor"),
("bridges_help","If connecting gets stuck at 5–10%, Tor is blocked for you — you need bridges. Auto tries every method until one works and remembers it.","Если подключение застревает на 5–10%, Tor у вас блокируют — нужны мосты. «Авто» сам перебирает все способы, пока один не сработает, и запоминает удачный."),
("bridge_auto","Auto — keep trying until connected (recommended)","Авто — перебирать, пока не подключится (рекомендуется)"),
("bridge_direct","No bridges","Без мостов"),
("bridge_obfs4","obfs4 — built-in bridges","obfs4 — встроенные мосты"),
("bridge_snowflake","Snowflake — via volunteers, gets around most blocking","Snowflake — через волонтёров, обходит большинство блокировок"),
("bridge_dnstt","dnstt — over Google DNS, slow but very hard to block","dnstt — через DNS Google, медленно, но почти не блокируется"),
("bridge_meek","meek — via CDN","meek — через CDN"),
("bridge_custom","Custom bridges","Свои мосты"),
("custom_bridges_hint","Custom bridges (one per line)","Свои мосты (по строке на мост)"),
("custom_bridges_helper","obfs4, webtunnel, snowflake… Get them from the Telegram bot @GetBridgesBot, bridges.torproject.org or the button below. Auto tries them first.","obfs4, webtunnel, snowflake… Взять: Telegram-бот @GetBridgesBot, bridges.torproject.org или кнопка ниже. В «Авто» пробуются первыми."),
("fetch_bridges","Get fresh bridges from Tor Project","Запросить свежие мосты у Tor Project"),
("fetching_bridges","Requesting bridges…","Запрашиваю мосты…"),
("fetched_bridges","Bridges received: %1$d","Получено мостов: %1$d"),
("fetch_failed","Could not get bridges: %1$s","Не удалось получить мосты: %1$s"),
("vpn_denied","VPN mode can't be turned on without permission","Без разрешения VPN режим не включить"),
("proxy_title","Proxy for other apps","Прокси для других приложений"),
("socks_line","SOCKS5  127.0.0.1:%1$s","SOCKS5  127.0.0.1:%1$s"),
("http_line","HTTP    127.0.0.1:%1$s","HTTP    127.0.0.1:%1$s"),
("proxy_help","Tap a line to copy the address. Use it in a browser (Firefox, Kiwi), Telegram or Wi-Fi settings → Proxy (HTTP port, works for HTTPS sites).","Нажмите на строку, чтобы скопировать адрес. Укажите его в браузере (Firefox, Kiwi), Telegram или в настройках Wi-Fi → Прокси (HTTP-порт, работает для HTTPS-сайтов)."),
("copied","Copied: %1$s","Скопировано: %1$s"),
("tg_title","Telegram over Tor","Telegram через Tor"),
("tg_help","Telegram opens with the proxy filled in — just tap Connect. Messages and files go through Tor, calls don't (Tor doesn't carry UDP).","Telegram откроется с готовым прокси — останется нажать «Подключить». Сообщения и файлы идут через Tor, звонки — нет (Tor не передаёт UDP)."),
("tg_connect","Connect Telegram to the proxy","Подключить Telegram к прокси"),
("tg_not_installed","Telegram is not installed","Telegram не установлен"),
("tor_not_running","Start Tor first","Сначала запустите Tor"),
("lan_share","Share the proxy over Wi-Fi and hotspot","Раздавать прокси в Wi-Fi и точке доступа"),
("lan_address","SOCKS5  %1$s:%2$d","SOCKS5  %1$s:%2$d"),
("lan_no_network","SOCKS5  port %1$d — no Wi-Fi or hotspot","SOCKS5  порт %1$d — нет Wi-Fi или точки доступа"),
("lan_send_link","Send a link for Telegram","Отправить ссылку для Telegram"),
("lan_help","To route a computer or another phone on the same network (or on your hotspot) through Tor: open the t.me/socks link on it or set the proxy manually. The proxy has no password — only turn this on in your own network.","Чтобы компьютер или другой телефон в той же сети (или подключённый к вашей точке доступа) ходил через Tor: откройте на нём ссылку t.me/socks или укажите прокси вручную. Прокси без пароля — включайте только в своей сети."),
("log","Log","Журнал"),
("language_title","Language","Язык"),
("language_system","System","Системный"),
("language_en","English","English"),
("language_ru","Русский","Русский"),
# названия способов подключения
("mode_auto","Auto","Авто"),
("mode_direct","no bridges","без мостов"),
("mode_snowflake","Snowflake","Snowflake"),
("mode_obfs4","obfs4","obfs4"),
("mode_meek","meek","meek"),
("mode_dnstt","dnstt (over DNS)","dnstt (через DNS)"),
("mode_moat","bridges from Tor Project","мосты от Tor Project"),
("mode_custom","custom bridges","свои мосты"),
# журнал
("log_tor_error","Tor error: %1$s","Ошибка Tor: %1$s"),
("log_new_method","New connection method: %1$s","Новый способ подключения: %1$s"),
("log_new_settings","New settings: %1$s","Новые настройки: %1$s"),
("log_starting","Starting Tor… (%1$s)","Запуск Tor… (%1$s)"),
("log_torservice_failed","Could not start TorService","Не удалось запустить TorService"),
("log_no_custom","No custom bridges set","Свои мосты не заданы"),
("log_moat_request","Requesting bridges from Tor Project (Moat)…","Запрашиваю мосты у Tor Project (Moat)…"),
("log_moat_got","Moat returned %1$d bridges","Moat выдал мостов: %1$d"),
("log_moat_failed","Moat unavailable: %1$s","Moat недоступен: %1$s"),
("log_moat_http","Moat returned HTTP %1$d","Moat ответил %1$d"),
("log_next_round","Nothing has worked yet, trying all methods again","Ни один способ пока не сработал, пробую по второму кругу"),
("log_transport_start_failed","%1$s: could not start the transport: %2$s","%1$s: не удалось запустить транспорт: %2$s"),
("log_transport_error","Transport %1$s: %2$s","Транспорт %1$s: %2$s"),
("log_transport_stopped","Transport %1$s stopped: %2$s","Транспорт %1$s остановлен: %2$s"),
("log_connecting","Connecting: %1$s","Подключаюсь: %1$s"),
("log_connecting_bridges","Connecting: %1$s (%2$d bridges)","Подключаюсь: %1$s (мостов: %2$d)"),
("log_bridges_apply_failed","Could not apply bridges: %1$s","Не удалось применить мосты: %1$s"),
("log_stall_next","%1$s: no progress for %2$d s, trying the next method","%1$s: нет прогресса %2$d с, пробую дальше"),
("log_stall_hint","%1$s: connection is stuck. Try Auto mode or other bridges","%1$s: подключение застряло. Попробуйте режим «Авто» или другие мосты"),
("log_bootstrap","Connecting: %1$d%% %2$s","Подключение: %1$d%% %2$s"),
("log_connected","Tor connected (%1$s). SOCKS5 127.0.0.1:%2$d, HTTP 127.0.0.1:%3$d","Tor подключён (%1$s). SOCKS5 127.0.0.1:%2$d, HTTP 127.0.0.1:%3$d"),
("log_done","Done: IP changed %1$d times. Tor keeps running.","Готово: IP сменён %1$d раз. Tor продолжает работать."),
("log_not_connected","Tor is not connected yet","Tor ещё не подключён"),
("log_rate_limit","Tor allows changing IP at most once every 10 s, wait %1$d s","Tor разрешает менять IP не чаще раза в 10 с, подождите %1$d с"),
("log_newnym_failed","NEWNYM failed: %1$s","NEWNYM не прошёл: %1$s"),
("log_change_manual","Changing IP…","Смена IP вручную…"),
("log_change_timer","Changing IP on timer…","Смена IP по таймеру…"),
("log_new_ip","New IP: %1$s","Новый IP: %1$s"),
("log_same_ip","IP unchanged: %1$s (same exit relay)","IP пока прежний: %1$s (выход совпал)"),
("log_ip_failed","Could not get the IP: %1$s","Не удалось узнать IP: %1$s"),
("log_lan_failed","Could not open the proxy to the network: %1$s","Не удалось открыть прокси для сети: %1$s"),
("log_lan_on","Proxy open to other devices: SOCKS5 port %1$d","Прокси открыт для других устройств: SOCKS5 порт %1$d"),
("log_lan_off","Proxy closed to other devices","Прокси для других устройств закрыт"),
("log_stopping","Stopping Tor","Остановка Tor"),
("err_no_response","no response","нет ответа"),
("describe_every","every %1$d s","каждые %1$d с"),
("describe_every_range","every %1$d–%2$d s","каждые %1$d–%2$d с"),
("describe_unlimited","unlimited","без ограничения"),
("describe_times","%1$d times","%1$d раз"),
# уведомление
("notif_ip","IP: %1$s","IP: %1$s"),
("notif_connecting","Connecting to Tor: %1$d%%","Подключение к Tor: %1$d%%"),
("notif_changes","IP changes: %1$d · %2$s","Смен IP: %1$d · %2$s"),
("notif_changes_of","IP changes: %1$d of %2$d · %3$s","Смен IP: %1$d из %2$d · %3$s"),
("notif_vpn","VPN: all traffic","VPN: весь трафик"),
("notif_starting","Starting…","Запуск…"),
# VPN
("vpn_log_no_port","VPN: unknown Tor SOCKS port","VPN: неизвестен SOCKS-порт Tor"),
("vpn_log_no_permission","VPN: no permission, turn VPN mode on from the app","VPN: нет разрешения, включите режим VPN из приложения"),
("vpn_log_iface_failed","VPN: could not create the interface: %1$s","VPN: не удалось создать интерфейс: %1$s"),
("vpn_log_tun2socks_failed","VPN: tun2socks did not start: %1$s","VPN: tun2socks не запустился: %1$s"),
("vpn_log_on","VPN on: all phone traffic goes through Tor","VPN включён: весь трафик телефона идёт через Tor"),
("vpn_log_off","VPN off","VPN выключен"),
("vpn_log_revoked","VPN was turned off by the system","VPN отключён системой"),
("exit_title","Exit country (priority region)","Страна выхода (приоритетный регион)"),
("exit_any","Any country","Любая страна"),
("exit_help","Your IP will only be from the selected countries. Fewer countries means fewer exit relays: slower, and sometimes no connection at all.","IP будет только из выбранных стран. Чем меньше стран — тем меньше выходных узлов: медленнее, а иногда и не подключиться."),
("exit_pick_title","Exit countries","Страны выхода"),
("log_exit_set","Exit countries: %1$s","Страны выхода: %1$s"),
("log_exit_any","Exit country: any","Страна выхода: любая"),
("log_exit_no_geoip","No GeoIP database in this build — exit country selection is unavailable","В этой сборке нет базы GeoIP — выбор страны выхода недоступен"),
]
def esc(v):
    v = escape(v).replace("'", "\\'")
    if v.startswith("@") or v.startswith("?"): v = "\\" + v
    return v
def write(path, idx):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for k, en, ru in S:
        val = (en, ru)[idx]
        extra = ' translatable="false"' if idx == 0 and k in ("app_name", "dash", "socks_line", "http_line", "lan_address", "language_en", "language_ru", "mode_snowflake", "mode_obfs4", "mode_meek") else ""
        if idx == 1 and extra == "" and k in ("app_name", "dash", "socks_line", "http_line", "lan_address", "language_en", "language_ru", "mode_snowflake", "mode_obfs4", "mode_meek"):
            continue
        lines.append(f'    <string name="{k}"{extra}>{esc(val)}</string>')
    lines.append("</resources>")
    open(path, "w").write("\n".join(lines) + "\n")
base = sys.argv[1]
import os
os.makedirs(base + "/values-ru", exist_ok=True)
write(base + "/values/strings.xml", 0)
write(base + "/values-ru/strings.xml", 1)
assert len({k for k,_,_ in S}) == len(S)
print(len(S), "strings")

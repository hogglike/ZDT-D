#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Single-profile prototype. Not an installer. Run as root; stop after five minutes.
PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin
export PATH
unset LD_PRELOAD LD_LIBRARY_PATH
umask 077
if ! command -v awk >/dev/null 2>&1; then
  awk() { /data/adb/modules/ZDT-D/bin/busybox awk "$@"; }
fi
if ! command -v setsid >/dev/null 2>&1; then
  setsid() { /data/adb/modules/ZDT-D/bin/busybox setsid "$@"; }
fi
work=/data/local/tmp/zdtd-dns-one
report=/sdcard/Download/dns-one-report.txt
netid=28200
tun=zdt_dns_one
chain=ZDT_DNS_ONE_V6
bin=/data/adb/modules/ZDT-D/bin/sing-box
exec 3>&1
[ "$(id -u)" = 0 ] || { echo 'Сначала введи su, затем повтори команду.'; exit 1; }

say() { printf '%s\n' "$*"; printf '%s\n' "$*" >&3; }
run() {
  if [ -f "$work/stop" ] && [ "${cleaning:-0}" != 1 ]; then out='stop requested'; return 1; fi
  out=$(timeout 25 "$@" </dev/null 2>&1)
  code=$?
  printf '\ncommand:'; printf ' %s' "$@"; printf '\n%s\nexit=%s\n' "$out" "$code"
  return "$code"
}
ndc_run() {
  run ndc "$@" || return 1
  printf '%s\n' "$out" | awk '$1 ~ /^[45][0-9][0-9]$/ {bad=1} $1=="200" {ok=1} END {exit !(ok && !bad)}'
}
helper() { run env CLASSPATH="$work/control.dex" app_process /system/bin DnsProfileControl "$@"; }
pid_start() { sed 's/.*) //' "/proc/$1/stat" 2>/dev/null | awk '$1 != "Z" {print $20}'; }
same_pid() {
  [ -f "$1" ] || return 1
  read -r saved_pid saved_start < "$1"
  case "$saved_pid:$saved_start" in *[!0-9:]*|:|*:) return 1;; esac
  [ "$saved_start" = "$(pid_start "$saved_pid")" ] && kill -0 "$saved_pid" 2>/dev/null
}
trusted_work() {
  [ -d "$work" ] && [ ! -L "$work" ] && [ "$(stat -c %u "$work")" = 0 ] && [ "$(stat -c %a "$work")" = 700 ]
}
cleanup() {
  cleaning=1
  [ -d "$work" ] || return 0
  # Never use a previous boot's journal to remove a new boot's resources.
  if [ "$(cat "$work/boot" 2>/dev/null)" != "$(cat /proc/sys/kernel/random/boot_id)" ]; then
    say 'Предыдущий запуск был до перезагрузки; сетевые ресурсы не трогаю.'
    rm -rf "$work"
    return 0
  fi
  if [ -f "$work/network" ]; then
    if [ -f "$work/uid" ]; then
      while read -r uid; do
        case "$uid" in *[!0-9]*|'') say 'Некорректный UID в журнале. Очистка остановлена.'; return 1;; esac
        ndc_run network users remove "$netid" "$uid-$uid" || true
      done < "$work/uid"
    fi
    if ! ndc_run network destroy "$netid"; then
      say 'Не удалось удалить тестовую сеть. Выполни dns-one.sh stop; журнал сохранён.'
      return 1
    fi
    rm -f "$work/network" "$work/uid" "$work/cache"
  elif [ -f "$work/cache" ]; then
    helper destroy || return 1
    rm -f "$work/cache"
  fi
  if [ -f "$work/v6hook" ]; then
    run ip6tables -w 5 -D OUTPUT -j "$chain" || return 1
    rm -f "$work/v6hook"
  fi
  if [ -f "$work/v6chain" ]; then
    run ip6tables -w 5 -F "$chain" || return 1
    run ip6tables -w 5 -X "$chain" || return 1
    rm -f "$work/v6chain"
  fi
  if same_pid "$work/child.pid"; then
    kill -TERM "$saved_pid" || return 1
    for attempt in 1 2 3 4 5; do same_pid "$work/child.pid" || break; sleep 1; done
    if same_pid "$work/child.pid"; then kill -KILL "$saved_pid" || return 1; fi
  fi
  for attempt in 1 2 3 4 5; do
    ip link show "$tun" >/dev/null 2>&1 || break
    sleep 1
  done
  # TUN is owned by the forwarder's FD and disappears when that process exits.
  if ip link show "$tun" >/dev/null 2>&1; then
    say 'Тестовый TUN ещё существует; очистка не подтверждена. Журнал сохранён.'
    return 1
  fi
  helper verify-empty || return 1
  # Allow stop to clean a journal produced by prototype 0.1 as well.
  if [ ! -f "$work/selected.uids" ] && [ -f "$work/youtube.uid" ]; then cp "$work/youtube.uid" "$work/selected.uids"; fi
  restored_ok=1
  if [ -f "$work/selected.uids" ]; then
    while read -r app_uid; do
      case "$app_uid" in *[!0-9]*|'') return 1;; esac
      if ip rule show | grep -q "uidrange $app_uid-$app_uid.*lookup $tun"; then
        say 'Осталось правило привязки; журнал сохранён.'; return 1
      fi
      helper query-uid "$app_uid" system || restored_ok=0
    done < "$work/selected.uids"
    printf 'post_cleanup_system_check=%s\n' "$restored_ok"
  fi
  printf '\n--- sing-box log ---\n'
  tail -n 150 "$work/sing-box.log" 2>/dev/null || true
  rm -rf "$work"
  say 'CLEANUP_OK: тестовый профиль отключён.'
}

case "${1:-start}" in
  __watch)
    trusted_work || exit 1
    exec >> "$report" 2>&1
    watch_end=$(awk '{printf "%.0f", $1+600}' /proc/uptime)
    while [ -d "$work" ] && same_pid "$work/main.pid"; do
      now=$(awk '{printf "%.0f", $1}' /proc/uptime)
      if [ "$now" -ge "$watch_end" ]; then
        : > "$work/stop"
        kill -TERM "$saved_pid" 2>/dev/null || true
      fi
      sleep 2
    done
    if [ -d "$work" ]; then
      say 'WATCHDOG: основной процесс завершился; выполняю очистку.'
      cleanup || say 'WATCHDOG_CLEANUP_FAILED: выполни dns-one.sh stop.'
    fi
    exit 0
    ;;
  stop)
    if [ ! -e "$work" ]; then echo 'Тестовый профиль не запущен.'; exit 0; fi
    trusted_work || { echo 'Неожиданные права рабочей папки; остановка отменена.'; exit 1; }
    if same_pid "$work/main.pid"; then
      : > "$work/stop"
      echo 'Остановка запрошена. Дождись CLEANUP_OK в окне запуска.'
      exit 0
    fi
    exec >> "$report" 2>&1
    cleanup
    exit $?
    ;;
  start) ;;
  *) echo 'Используй start или stop.'; exit 1;;
esac

# Atomic private directory acquisition; never overwrite an existing session.
mkdir "$work" 2>/dev/null || { echo 'Есть предыдущий запуск. Сначала выполни dns-one.sh stop.'; exit 1; }
chmod 700 "$work"
cat /proc/sys/kernel/random/boot_id > "$work/boot"
printf '%s %s\n' "$$" "$(pid_start $$)" > "$work/main.pid"
if ! : > "$report"; then rm -rf "$work"; echo 'Не удалось создать отчёт в Download.'; exit 1; fi
exec > "$report" 2>&1
finished=0
finish() {
  result=$?
  trap - EXIT INT TERM HUP
  if [ "$finished" != 1 ]; then
    # Before any resources exist, only the private files require cleanup.
    if [ -f "$work/resources" ]; then cleanup || result=1; else rm -rf "$work"; fi
  fi
  say "Отчёт: $report"
  exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
fail() { say "ОСТАНОВЛЕНО: $*"; exit 1; }

echo 'ZDT-D one-profile prototype 0.2.1; Chrome+Gemini=Xbox DoH, YouTube=system'
run id || fail 'Нет root.'
[ -x "$bin" ] || fail 'Не найден sing-box установленного ZDT-D.'
awk 'BEGIN {exit 0}' || fail 'Не найден awk (системный или BusyBox ZDT-D).'
setsid /system/bin/true || fail 'Не найден setsid для процесса восстановления.'
run "$bin" version || fail 'sing-box не запускается.'
case "$out" in *'sing-box version 1.13.'*) ;; *) fail 'Прототип рассчитан на sing-box 1.13.x.';; esac
run settings get global private_dns_mode || fail 'Не удалось прочитать Private DNS.'
case "$out" in off|null|'') ;; *) fail 'Для теста выключи Private DNS в настройках Android.';; esac

# Do not modify DNSCrypt config or stop unrelated engines behind the user's back.
if grep -Eq '"enabled"[[:space:]]*:[[:space:]]*true' /data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json; then
  fail 'Выключи общий DNSCrypt в ZDT-D и повтори запуск. Его конфиг сохранится.'
fi
no_redirects() {
  for save_cmd in iptables-save ip6tables-save; do
    snapshot=$(timeout 10 "$save_cmd" </dev/null 2>&1)
    save_code=$?
    if [ "$save_code" != 0 ]; then
      say "FIREWALL_READ_ERROR: $save_cmd exit=$save_code"
      printf '%s\n' "$snapshot"
      return 1
    fi
    # Follow jumps/gotos from local egress hooks in each table. Unused chains
    # and chains reachable only from PREROUTING/FORWARD do not block this test.
    # Match conditions are deliberately not evaluated: a reachable rule still
    # causes a conservative refusal, even if it might match another UID only.
    conflicts=$(printf '%s\n' "$snapshot" | awk '
      /^\*/ {
        table=substr($0,2)
        reachable[table SUBSEP "OUTPUT"]=1
        reachable[table SUBSEP "POSTROUTING"]=1
      }
      $1=="-A" {
        if (table=="") {invalid=1; next}
        n++; source[n]=table SUBSEP $2; lines[n]="table=" table " " $0
        for (i=3;i<NF;i++) if ($i=="-j" || $i=="-g" || $i=="--jump" || $i=="--goto") {
          dest[n]=table SUBSEP $(i+1)
          blocked[n]=($(i+1) ~ /^(DNAT|REDIRECT|TPROXY|NFQUEUE)$/)
          break
        }
      }
      /^COMMIT/ {table=""}
      END {
        if(invalid) {print "Cannot parse firewall table boundaries"; exit 2}
        changed=1
        while(changed) {
          changed=0
          for(i=1;i<=n;i++) if(reachable[source[i]] && dest[i]!="" && !reachable[dest[i]]) {
            reachable[dest[i]]=1; changed=1
          }
        }
        for(i=1;i<=n;i++) if(reachable[source[i]] && blocked[i]) print lines[i]
      }')
    parse_code=$?
    if [ "$parse_code" != 0 ]; then
      say "FIREWALL_PARSE_ERROR: $save_cmd exit=$parse_code"
      printf '%s\n' "$conflicts\n$snapshot"
      return 1
    fi
    if [ -n "$conflicts" ]; then
      say "FIREWALL_CONFLICT: $save_cmd — правила в цепочках исходящего трафика:"
      printf '%s\n' "$conflicts"
      return 1
    fi
  done
  return 0
}
no_redirects || fail 'Проверка сетевых правил не пройдена. Причина записана выше в отчёте; профиль не запущен.'
out=$(timeout 25 cmd package list packages -U </dev/null 2>&1) || fail 'Не удалось прочитать UID приложений.'
printf '%s\n' "$out" > "$work/packages"
youtube_uid=$(awk '$1=="package:com.google.android.youtube" {sub(/^uid:/,"",$2); print $2}' "$work/packages")
chrome_uid=$(awk '$1=="package:com.android.chrome" {sub(/^uid:/,"",$2); print $2}' "$work/packages")
gemini_uid=$(awk '$1=="package:com.google.android.apps.bard" {sub(/^uid:/,"",$2); print $2}' "$work/packages")
for app_uid in "$youtube_uid" "$chrome_uid" "$gemini_uid"; do
  case "$app_uid" in ''|*[!0-9]*) fail 'Не найден Chrome, Gemini (com.google.android.apps.bard) или контрольный YouTube.';; esac
  [ "$app_uid" -ge 10000 ] && [ "$app_uid" -le 19999 ] || fail 'Поддерживается основной пользователь Android.'
  count=$(awk -v u="uid:$app_uid" '$2==u {n++} END {print n+0}' "$work/packages")
  [ "$count" = 1 ] || fail 'Общий UID у нескольких пакетов: тест не запущен.'
done
[ "$youtube_uid" != "$chrome_uid" ] && [ "$youtube_uid" != "$gemini_uid" ] && [ "$chrome_uid" != "$gemini_uid" ] || fail 'UID тестовых приложений совпали.'
selected_uids="$chrome_uid $gemini_uid"
printf '%s\n' "$chrome_uid" "$gemini_uid" > "$work/selected.uids"
printf '%s %s\n' com.android.chrome "$chrome_uid" com.google.android.apps.bard "$gemini_uid" > "$work/selection"
say "Xbox DNS: Chrome UID=$chrome_uid; Gemini UID=$gemini_uid. Контроль: YouTube UID=$youtube_uid"
run ip rule show || fail 'Не удалось прочитать правила маршрутизации.'
if printf '%s\n' "$out" | awk -v u="$youtube_uid" -v c="$chrome_uid" -v g="$gemini_uid" '{for(i=1;i<NF;i++) if($i=="uidrange") {split($(i+1),a,"-"); if((u>=a[1] && u<=a[2]) || (c>=a[1] && c<=a[2]) || (g>=a[1] && g<=a[2])) found=1}} END {exit !found}'; then
  fail 'Одно из тестовых приложений уже привязано к другой виртуальной сети.'
fi
ip link show "$tun" >/dev/null 2>&1 && fail 'Имя тестового TUN уже занято.'
ip6tables -w 5 -S "$chain" >/dev/null 2>&1 && fail 'Тестовая цепочка IPv6 уже занята.'
run ip -4 route show table all || fail 'Не удалось проверить подсеть.'
printf '%s\n' "$out" | grep -q '10\.253\.241\.' && fail 'Тестовая подсеть занята.'

base64 -d > "$work/control.dex" <<'ZDNS_DEX'
@@DEX_BASE64@@
ZDNS_DEX
[ $? = 0 ] || fail 'Ошибка извлечения клиента Android.'
sum=$(sha256sum "$work/control.dex"); sum=${sum%% *}
[ "$sum" = '@@DEX_SHA256@@' ] || fail 'Не совпала контрольная сумма клиента Android.'
chmod 400 "$work/control.dex"
cat > "$work/config.json" <<'ZDNS_CONFIG'
@@CONFIG@@
ZDNS_CONFIG
run "$bin" check -c "$work/config.json" || fail 'sing-box отклонил конфигурацию.'
helper query-uid "$youtube_uid" system || fail 'Исходный системный DNS YouTube не прошёл проверку.'
helper query-uid "$chrome_uid" system || fail 'Исходный системный DNS Chrome не прошёл проверку.'
helper query-uid "$gemini_uid" system || fail 'Исходный системный DNS Gemini не прошёл проверку.'
cp "$0" "$work/runner.sh" || fail 'Не удалось подготовить процесс восстановления.'
setsid /system/bin/sh "$work/runner.sh" __watch </dev/null >/dev/null 2>&1 &
watch_pid=$!
printf '%s %s\n' "$watch_pid" "$(pid_start "$watch_pid")" > "$work/watch.pid"
same_pid "$work/watch.pid" || fail 'Процесс восстановления не запустился.'

# First mutation: acquire cache ownership atomically; EEXIST means stop, never reuse.
helper create || fail 'Не удалось создать новый кэш. Занятый кэш не изменён.'
: > "$work/cache"
: > "$work/resources"
ndc_run network create "$netid" vpn 1 || fail 'netd не создал отдельную сеть.'
: > "$work/network"
"$bin" run -c "$work/config.json" > "$work/sing-box.log" 2>&1 &
child=$!
printf '%s %s\n' "$child" "$(pid_start "$child")" > "$work/child.pid"
ready=0
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  same_pid "$work/child.pid" || fail 'Процесс sing-box завершился при запуске.'
  if ip link show "$tun" >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || fail 'TUN не появился.'
ndc_run network interface add "$netid" "$tun" || fail 'Не удалось добавить TUN в сеть.'
ndc_run network route add "$netid" "$tun" 10.253.241.0/30 || fail 'Не удалось добавить маршрут DNS.'
ndc_run network route add "$netid" "$tun" 0.0.0.0/0 || fail 'Не удалось добавить DIRECT-маршрут.'
helper configure || fail 'Настройка DNS или её чтение не прошли.'
helper query-network || fail 'DNS-маркер или Xbox DoH через тестовую сеть не отвечает.'

# IPv6 policy must be installed and checked BEFORE any app is attached.
run ip6tables -w 5 -N "$chain" || fail 'Не удалось создать защиту IPv6.'
: > "$work/v6chain"
for app_uid in $selected_uids; do
  run ip6tables -w 5 -A "$chain" -m owner --uid-owner "$app_uid" ! -o lo -j REJECT || fail 'Не удалось ограничить IPv6 выбранного приложения.'
done
run ip6tables -w 5 -I OUTPUT 1 -j "$chain" || fail 'Не удалось подключить защиту IPv6.'
: > "$work/v6hook"
run ip6tables -w 5 -C OUTPUT -j "$chain" || fail 'Защита IPv6 не подтверждена.'
for app_uid in $selected_uids; do
  run ip6tables -w 5 -C "$chain" -m owner --uid-owner "$app_uid" ! -o lo -j REJECT || fail 'Правило IPv6 не подтверждено.'
done
# UID intent is journalled before the command; destroying our network removes its UID ranges.
cp "$work/selected.uids" "$work/uid"
for app_uid in $selected_uids; do
  ndc_run network users add "$netid" "$app_uid-$app_uid" || fail 'Привязка выбранного приложения не прошла.'
  helper query-uid "$app_uid" profile || fail 'DNS выбранного приложения не попал в профиль Xbox.'
done
helper query-uid "$youtube_uid" system || fail 'Профиль затронул контрольный DNS YouTube.'
say 'READY: Chrome и Gemini → Xbox DoH; контрольный UID YouTube → системный DNS.'
say 'Открой Chrome и Gemini. Через 5 минут профиль отключится. Termux можно свернуть.'
say 'Чтобы остановить раньше: /system/bin/sh /sdcard/Download/dns-one.sh stop'

end=$(awk '{printf "%.0f", $1+300}' /proc/uptime)
while [ "$(awk '{printf "%.0f", $1}' /proc/uptime)" -lt "$end" ]; do
  [ -f "$work/stop" ] && break
  same_pid "$work/child.pid" || fail 'sing-box завершился; выполняю откат.'
  same_pid "$work/watch.pid" || fail 'Процесс восстановления завершился; выполняю откат.'
  no_redirects || fail 'Появилось другое перенаправление; выполняю откат.'
  ip6tables -w 5 -C OUTPUT -j "$chain" >/dev/null 2>&1 || fail 'Пропала защита IPv6; выполняю откат.'
  while read -r package app_uid; do
    ip6tables -w 5 -C "$chain" -m owner --uid-owner "$app_uid" ! -o lo -j REJECT >/dev/null 2>&1 || fail 'Пропало правило IPv6; выполняю откат.'
    current_uid=$(timeout 10 cmd package list packages -U "$package" </dev/null 2>&1) || fail 'Не удалось повторно проверить UID.'
    current_uid=$(printf '%s\n' "$current_uid" | awk -v p="package:$package" '$1==p {sub(/^uid:/,"",$2); print $2}')
    [ "$current_uid" = "$app_uid" ] || fail 'UID выбранного приложения изменился; выполняю откат.'
  done < "$work/selection"
  sleep 5
done
if [ -f "$work/stop" ]; then
  cleanup || fail 'Очистка не завершилась; используй команду stop.'
  finished=1
  say 'STOPPED: профиль отключён по запросу.'
  exit 0
fi
for app_uid in $selected_uids; do
  helper query-uid "$app_uid" profile || fail 'Профиль перестал отвечать к концу теста.'
done
helper query-uid "$youtube_uid" system || fail 'Контрольный системный DNS перестал отвечать.'
cleanup || fail 'Очистка не завершилась; используй команду stop.'
finished=1
[ "$restored_ok" = 1 ] || fail 'Профиль удалён, но проверка обычного интернета после отключения не прошла.'
say 'TEST_COMPLETE: автоматические проверки прошли. Работа самого приложения оценивается отдельно.'
exit 0

# Complete sources, license texts and provenance:
@@SOURCE_COMMENTS@@

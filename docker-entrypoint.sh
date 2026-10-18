#!/bin/sh
set -e

# Oracle ADB wallet. cwallet.sso is binary and gets corrupted when pasted as a
# text secret file, so it is supplied base64-encoded in WALLET_CWALLET_B64.
# tnsnames.ora / ojdbc.properties hold no secrets and are generated here, so
# the only wallet secret needed on the platform is WALLET_CWALLET_B64.
if [ -n "$WALLET_CWALLET_B64" ]; then
  WALLET_TARGET="/tmp/wallet"
  mkdir -p "$WALLET_TARGET"
  printf '%s' "$WALLET_CWALLET_B64" | base64 -d > "$WALLET_TARGET/cwallet.sso"

  cat > "$WALLET_TARGET/tnsnames.ora" <<TNS
ecommerce_tp = (description= (retry_count=20)(retry_delay=3)(address=(protocol=tcps)(port=1522)(host=${DB_HOST:-adb.ap-mumbai-1.oraclecloud.com}))(connect_data=(service_name=${DB_SERVICE_NAME:-g71e114c9178818_ecommerce_tp.adb.oraclecloud.com}))(security=(ssl_server_dn_match=yes)))
TNS
  echo 'SSL_SERVER_DN_MATCH=yes' > "$WALLET_TARGET/sqlnet.ora"
  echo 'oracle.net.wallet_location=(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=${TNS_ADMIN})))' > "$WALLET_TARGET/ojdbc.properties"
  export WALLET_DIR="$WALLET_TARGET"
fi

exec java -XX:MaxRAMPercentage=75.0 -jar app.jar

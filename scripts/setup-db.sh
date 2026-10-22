#!/usr/bin/env bash
# Creates the disposable local databases and application user.
set -euo pipefail
mariadb -uroot -e "
  CREATE DATABASE IF NOT EXISTS lostfound CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE DATABASE IF NOT EXISTS lostfound_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE DATABASE IF NOT EXISTS laf_handover_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER IF NOT EXISTS 'lostfound'@'localhost' IDENTIFIED BY 'lostfound';
  GRANT ALL PRIVILEGES ON lostfound.* TO 'lostfound'@'localhost';
  GRANT ALL PRIVILEGES ON lostfound_test.* TO 'lostfound'@'localhost';
  GRANT ALL PRIVILEGES ON laf_handover_test.* TO 'lostfound'@'localhost';
  FLUSH PRIVILEGES;"
echo "Databases lostfound, lostfound_test and laf_handover_test are ready."

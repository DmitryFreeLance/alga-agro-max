# Deploy

Все нужные файлы для выкладки лежат прямо в `alga-agro-max`:

- `Dockerfile`
- `docker-compose.yml`
- `env.server.example`
- `run-classic-deploy.sh`
- `server_catalog_sync.sql`

## Привычный запуск

Если хочешь запускать в старом стиле `docker build` / `docker stop` / `docker rm` / `docker run`, используй:

```bash
bash ./run-classic-deploy.sh
```

Скрипт сам:

1. Соберет свежий image из текущего `alga-agro-max`
2. Пересоздаст контейнер `alga-agro-max`
3. Дождется, пока приложение создаст SQLite-схему
4. Подольет каталог из `server_catalog_sync.sql`
5. Перезапустит контейнер

Все основные переменные уже зашиты прямо в `run-classic-deploy.sh`, поэтому его можно запускать сразу. Если когда-нибудь захочешь переопределить значения без правки скрипта, можно дополнительно создать `.env.server`.

## Если запускать руками

Правильный порядок такой:

1. `docker build -t alga-agro-max .`
2. `docker stop alga-agro-max || true`
3. `docker rm alga-agro-max || true`
4. `docker run -d ...`
5. дождаться создания таблиц
6. `sqlite3 /opt/alga-agro/data/alga-agro.db < server_catalog_sync.sql`
7. `docker restart alga-agro-max`

## Важно

- `server_catalog_sync.sql` обновляет только каталог и производителей.
- Последние изменения mini app и отключение кэша уже внутри текущего кода.

# ALGA AGRO MAX

Java/Spring Boot проект для:

- MAX-бота на long polling
- мини аппа с каталогом, корзиной и оформлением заказа
- админских сценариев внутри бота
- импорта Excel-номенклатуры с AI-разметкой фильтров

## Что уже есть

- приветствие и стартовое меню бота с inline-кнопками
- кнопка открытия mini app
- админ-панель для админов
- список пользователей с пагинацией
- выдача прав админа
- список заказов с пагинацией
- сценарий загрузки Excel-файлов
- AI-классификация строк через Kie.ai `gpt-5-5`
- fallback через Kie.ai Gemini `3.5 Flash`
- сценарий постов: сбор медиа, ввод текста, предпросмотр, публикация
- постоянные кнопки к постам
- mini app в агро-стилистике
- корзина и checkout
- заявки из mini app падают админам в бот

## Структура

- `src/main/java/ru/algaagro/maxapp/service` — бизнес-логика, MAX API, AI, импорт Excel
- `src/main/java/ru/algaagro/maxapp/controller` — REST API и маршруты mini app
- `src/main/java/ru/algaagro/maxapp/model` — сущности SQLite
- `src/main/resources/static/miniapp` — frontend mini app

## Переменные окружения

Обязательные:

- `MAX_BOT_TOKEN` — токен бота MAX
- `APP_STARTUP_ADMIN_USER_IDS` — id стартовых админов через запятую, например `12345,67890`

Для mini app и домена:

- `APP_PUBLIC_BASE_URL` — например `https://algaagro.ru`
- `APP_MINI_APP_URL` — например `https://algaagro.ru/miniapp/`

Для публикации постов:

- `MAX_POST_TARGET_CHAT_ID` — `chat_id` группы/канала, куда публикуются посты

Для AI:

- `KIE_API_KEY`
- `KIE_MODEL` — по умолчанию `gpt-5-5`
- `KIE_BASE_URL` — по умолчанию `https://api.kie.ai`
- `KIE_GEMINI_API_KEY` — необязательно, по умолчанию берет `KIE_API_KEY`
- `KIE_GEMINI_MODEL` — по умолчанию `gemini-3-5-flash-openai`
- `KIE_GEMINI_ENDPOINT` — по умолчанию `/gemini-3-5-flash-openai/v1/chat/completions`

Дополнительно:

- `SERVER_PORT` — по умолчанию `8080`
- `APP_DB_PATH` — путь к sqlite, по умолчанию `/data/alga-agro.db`
- `MAX_POLL_TIMEOUT_SECONDS` — по умолчанию `30`
- `MAX_POLL_LIMIT` — по умолчанию `100`

## Локальный запуск

```bash
mvn clean package
java -jar target/alga-agro-max-1.0.0.jar
```

Mini app:

- `http://localhost:8080/miniapp/`

API:

- `GET /api/meta`
- `GET /api/catalog/filters`
- `GET /api/catalog/products`
- `POST /api/orders`

## Docker build

```bash
docker build -t alga-agro-max .
```

## Docker run

```bash
docker run -d \
  --name alga-agro-max \
  --restart unless-stopped \
  -p 8080:8080 \
  -v /opt/alga-agro/data:/data \
  -e TZ=Europe/Moscow \
  -e SERVER_PORT=8080 \
  -e APP_DB_PATH=/data/alga-agro.db \
  -e APP_PUBLIC_BASE_URL=https://algaagro.ru \
  -e APP_MINI_APP_URL=https://algaagro.ru/miniapp/ \
  -e MAX_BOT_TOKEN=your_max_token \
  -e APP_STARTUP_ADMIN_USER_IDS=123456789 \
  -e MAX_POST_TARGET_CHAT_ID=123456789 \
  -e KIE_API_KEY=your_kie_key \
  -e KIE_GEMINI_API_KEY=your_kie_gemini_key \
  alga-agro-max
```

## Nginx reverse proxy

Пример upstream на сервере:

```nginx
server {
    server_name algaagro.ru www.algaagro.ru;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    listen 443 ssl;
    ssl_certificate /etc/letsencrypt/live/algaagro.ru/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/algaagro.ru/privkey.pem;
}
```

## Как работает AI-разметка фильтров

После загрузки Excel:

1. Бот собирает строки из всех `.xlsx`.
2. Делит их на пачки.
3. Отправляет строки в Kie.ai `gpt-5-5`.
4. Если ответа нет или API упал, автоматически идет fallback на Kie.ai Gemini `3.5 Flash`.
5. ИИ возвращает:
   - категорию
   - подкатегорию
   - тип товара
   - культуры
   - назначение
   - теги
   - дополнительные фильтры
6. Эти данные сохраняются в каталог.
7. В mini app фильтр по культуре показывает все товары, где эта культура проставлена в AI-разметке.

Пример логики:

- выбрали `пшеница`
- показались семена пшеницы, фунгициды для пшеницы, гербициды для пшеницы, питание для пшеницы и сопутствующие позиции

## Админские команды

- `/start` — главное меню
- `/grant 123456` — выдать права админа пользователю, который уже запускал бота
- `/addbutton Группа | https://max.ru/...` — добавить постоянную кнопку к постам

## Важно

- сейчас проект сделан без Bitrix24, как ты и просил
- MAX long polling реализован намеренно, хотя для production MAX рекомендует webhook
- для Excel бот ожидает, что в сообщении вложения содержат ссылку на скачивание файла
- если AI недоступен, импорт завершится сообщением: `ИИ не удалось распознать, попробуйте позже.`

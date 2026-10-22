# CampusLost — Lost & Found

A lost & found management system: report lost/found items, search, claim, and resolve.

- **Frontend:** TanStack Start (React 19, Vite 8), TanStack Query, Tailwind CSS v4 — `frontend/`
- **Backend:** Java 26, Spring Boot 4.1, Spring Security + JWT, Spring Data JPA, MariaDB — `backend/`

## Prerequisites

- Java 26, Node 20+ (pnpm), MariaDB running locally

## Setup

```bash
# 1. Database (once)
sudo systemctl start mariadb
sudo mariadb -e "
  CREATE DATABASE IF NOT EXISTS lostfound CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  CREATE USER IF NOT EXISTS 'lostfound'@'localhost' IDENTIFIED BY 'lostfound';
  GRANT ALL PRIVILEGES ON lostfound.* TO 'lostfound'@'localhost';"
```

## Run

```bash
# 2. Backend (http://localhost:8080)
cd backend
./mvnw -DskipTests package
java -jar target/backend-0.0.1-SNAPSHOT.jar

# 3. Frontend (http://localhost:3000)
cd frontend
pnpm install
pnpm dev
```

Open http://localhost:3000. The frontend proxies `/api` to the backend; a
pristine seed dataset (users, 12 items, claims) is created automatically on
first boot.

## Demo accounts

| Role  | Email                  | Password |
|-------|------------------------|----------|
| Admin | admin@lostfound.dev    | admin123 |
| User  | alice@demo.dev         | demo123  |
| User  | bob@demo.dev           | demo123  |
| User  | carol@demo.dev         | demo123  |
| User  | dave@demo.dev          | demo123  |

## API overview

| Method | Path                       | Auth | Description                      |
|--------|----------------------------|------|----------------------------------|
| POST   | `/api/auth/register`       | —    | Create account                   |
| POST   | `/api/auth/login`          | —    | JWT login                        |
| GET    | `/api/auth/me`             | JWT  | Current user                     |
| GET    | `/api/items`               | —    | List/search (`type`, `category`, `location`, `q`) |
| GET    | `/api/items/{id}`          | —    | Item detail                      |
| POST   | `/api/items`               | JWT  | Report item                      |
| PATCH  | `/api/items/{id}/status`   | owner/admin | OPEN / RESOLVED / CLOSED |
| DELETE | `/api/items/{id}`          | owner/admin | Delete item               |
| GET    | `/api/items/me/reported`   | JWT  | My reported items                |
| POST   | `/api/items/{id}/claims`   | JWT  | Claim an item                    |
| GET    | `/api/items/{id}/claims`   | owner/admin | Claims on an item         |
| PATCH  | `/api/claims/{id}`         | owner/admin | APPROVED / REJECTED     |
| GET    | `/api/me/claims`           | JWT  | My claims                        |
| DELETE | `/api/claims/{id}`         | claimant | Cancel a pending claim       |
| GET    | `/api/admin/stats`         | ADMIN | Dashboard stats                 |
| GET    | `/api/admin/items`         | ADMIN | All items                        |

## Notes

- Approving a claim marks the item **RESOLVED** and auto-rejects other pending
  claims on it.
- For a Postgres/Neon migration later: swap the datasource URL/username/password
  (and the MySQL connector for the Postgres one) in `application.yml` — all JPA
  mapping uses standard SQL types.
- Seed data only runs when the `items` table is empty.

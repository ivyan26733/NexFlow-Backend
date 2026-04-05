# NexFlow EC2 Deployment Guide

## Architecture Overview

```
Vercel (Next.js Frontend)
        |
        | HTTPS
        v
Cloudflare Tunnel (https://positive-capital-bin-obtained.trycloudflare.com)
        |
        | HTTP (internal)
        v
EC2 Instance (43.204.28.197)
        |
    Nginx (port 80)
        |
    Spring Boot Backend (port 8090)
        |
    PostgreSQL + RabbitMQ (Docker containers)
```

---

## EC2 Instance Details

| Property | Value |
|----------|-------|
| Public IP | 43.204.28.197 |
| Instance ID | i-0fbb47426d1077d77 |
| OS | Amazon Linux 2023 |
| SSH Key | ~/Downloads/ec2/newNexflow.pem |
| User | ec2-user |

---

## SSH Into EC2

```bash
ssh -i /c/Users/hpcnd/Downloads/ec2/newNexflow.pem ec2-user@43.204.28.197
```

---

## Project Location on EC2

```
~/nexflow-backend/
├── docker-compose.prod.yml
├── Dockerfile
├── nginx.conf
├── .env                  ← secrets (never commit this)
├── rabbitmq-config/
├── src/
└── nexflow-assistant/    ← AI agent service (Node.js)
    ├── Dockerfile
    ├── index.js
    ├── agent.js
    ├── assistant.js
    ├── src/
    └── ...
```

> The `nexflow-assistant/` folder must be a sibling of `docker-compose.prod.yml` —
> that is how `docker-compose.prod.yml` builds the `nexflow-agent` image.

---

## Uploading nexflow-assistant to EC2

The `nexflow-assistant/` Node.js service lives in a separate folder locally.
Copy it to EC2 next to the backend files:

```bash
# Run from your LOCAL machine (Git Bash / WSL)
scp -i /c/Users/hpcnd/Downloads/ec2/newNexflow.pem -r \
  /d/NexFlow/nexflow-assistant \
  ec2-user@43.204.28.197:~/nexflow-backend/nexflow-assistant
```

After uploading, your EC2 `~/nexflow-backend/` should contain the
`nexflow-assistant/` folder with its `Dockerfile` inside.

---

## AI Agent Environment Variables

The `nexflow-agent` container reads its LLM keys from the same `.env` file.
After copying `.env.example` to `.env`, fill in the AI section:

```bash
nano ~/nexflow-backend/.env
```

```
LLM_PROVIDER=groq                        # groq | anthropic | gemini
GROQ_API_KEY=gsk_xxxxxxxxxxxxxxxxxxxx    # get free key at console.groq.com
```

Groq is the recommended default — it is free and fast.
Set `LLM_PROVIDER=anthropic` and `ANTHROPIC_API_KEY=sk-ant-...` to use Claude instead.

---

## Managing Docker Containers

```bash
# Go to project folder
cd ~/nexflow-backend

# Start all containers
docker compose -f docker-compose.prod.yml up -d

# Stop all containers
docker compose -f docker-compose.prod.yml down

# Stop and wipe all data (fresh start)
docker compose -f docker-compose.prod.yml down -v

# Restart a specific container
docker compose -f docker-compose.prod.yml restart backend

# View running containers
docker ps
```

---

## Checking Logs

```bash
# All containers live logs
docker compose -f docker-compose.prod.yml logs -f

# Specific container logs
docker logs nexflow-backend
docker logs nexflow-postgres
docker logs nexflow-rabbitmq
docker logs nexflow-nginx
docker logs nexflow-agent

# Last 100 lines
docker logs nexflow-backend --tail 100

# Cloudflare tunnel log
cat ~/cloudflared.log
tail -f ~/cloudflared.log
```

---

## Checking Database (PostgreSQL)

```bash
# Open postgres shell
docker exec -it nexflow-postgres psql -U postgres -d nexflow

# Inside psql — useful commands:
\dt                        -- list all tables
\d tablename               -- describe a table
SELECT * FROM users;       -- query a table
SELECT count(*) FROM users;
\q                         -- quit
```

---

## Cloudflare Tunnel

The tunnel exposes EC2's port 80 over HTTPS.

```bash
# Check if tunnel is running
ps aux | grep cloudflared

# View tunnel URL
grep "trycloudflare.com" ~/cloudflared.log

# Start tunnel in background
nohup cloudflared tunnel --url http://localhost:80 > ~/cloudflared.log 2>&1 &

# Stop tunnel
pkill cloudflared
```

> WARNING: The quick tunnel URL changes every time you restart it.
> Update Vercel env vars with the new URL after each restart.

---

## Vercel Environment Variables

| Key | Value |
|-----|-------|
| `NEXT_PUBLIC_API_URL` | `https://<tunnel-url>` |
| `NEXT_PUBLIC_WS_URL` | `wss://<tunnel-url>/ws` |

After updating env vars → **Redeploy** on Vercel.

---

## Health Check

```bash
# Test backend is alive
curl http://43.204.28.197/actuator/health
# Expected: {"status":"UP"}

# Test via tunnel
curl https://positive-capital-bin-obtained.trycloudflare.com/actuator/health
```

---

## .env File on EC2

Located at `~/nexflow-backend/.env`. To edit:

```bash
nano ~/nexflow-backend/.env
```

After editing, restart backend:
```bash
docker compose -f docker-compose.prod.yml restart backend
```

---

## RabbitMQ Management UI

RabbitMQ has a web UI on port 15672. To access it temporarily:

```bash
# On EC2, forward port to your machine
# Run this on your LOCAL Git Bash:
ssh -i /c/Users/hpcnd/Downloads/ec2/newNexflow.pem -L 15672:localhost:15672 ec2-user@43.204.28.197

# Then open in browser: http://localhost:15672
# Login: nexflow / nexflow_rabbit_pass
```

---

## When EC2 Restarts (After Reboot)

Docker containers restart automatically (`restart: unless-stopped` in compose).
But the Cloudflare tunnel does NOT restart automatically. Run:

```bash
nohup cloudflared tunnel --url http://localhost:80 > ~/cloudflared.log 2>&1 &
```

Then get the new URL and update Vercel:
```bash
grep "trycloudflare.com" ~/cloudflared.log
```

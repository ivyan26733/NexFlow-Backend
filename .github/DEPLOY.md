# Backend CI/CD: build on push to `master` and optional deploy

## What runs

- On every push to **master**: checkout → JDK 17 → **Maven build and tests** (`mvn package`).
- If deploy is enabled (see below), the workflow then **calls your host’s deploy webhook** so it pulls the latest code and deploys.

## 1. Build and test (always)

No configuration needed. The workflow builds and runs tests; if they fail, the job fails.

## 2. Deploy (optional)

To have the workflow **trigger a deploy** after a successful build:

1. **Create a deploy hook** on your backend host, for example:
   - **Railway:** Project → Settings → **Deploy hook** (or use their GitHub integration and skip the hook).
   - **Fly.io / Heroku / other:** Use the provider’s “deploy hook” or “build hook” URL if available.

2. **In your backend GitHub repo:**
   - **Settings → Secrets and variables → Actions**
     - **Secret:** `BACKEND_DEPLOY_HOOK_URL` = the hook URL from step 1.
     - **Variable:** `DEPLOY_BACKEND` = `true` (so the “Trigger deploy” step runs).

3. Push to **master**. After a successful build, the workflow will `POST` to the hook URL; your host will then build and deploy from the repo.

## Host-specific notes

- **Railway:** You can rely on Railway’s GitHub integration (auto-deploy on push) and leave the deploy step disabled, or use a deploy hook if your workflow uses one.
- **Other:** Any host that exposes a “deploy hook” or “build hook” URL works the same way: set that URL as `BACKEND_DEPLOY_HOOK_URL` and `DEPLOY_BACKEND=true`.

## Separate backend repo

Use this repo as your backend-only repo (root = this folder, with `pom.xml` at root). Copy or keep:

- `.github/workflows/deploy.yml`
- `.github/DEPLOY.md`

Add the secret and variable above in that repo’s GitHub Settings.

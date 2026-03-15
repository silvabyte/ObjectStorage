# Build & Deploy Workflow

CI automatically builds and pushes Docker images on every push to `main` — deployment is the only manual step.

## The Workflow

### 1. Push to `main`

```bash
git add <files>
git commit -m "feat: your change"
git push
```

### 2. Wait for CI

CI (`.github/workflows/ci.yml`) runs two jobs:
- **build**: compile, test, format-check, lint
- **docker** (only on `main`, after build passes): builds the Docker image and pushes to GHCR with two tags:
  - `ghcr.io/silvabyte/objectstorage:latest`
  - `ghcr.io/silvabyte/objectstorage:<7-char-sha>` (e.g., `e9c46be`)

Check CI status: `gh run list --limit 1` or look at GitHub Actions.

### 3. Deploy

```bash
make deploy              # uses current HEAD sha
# OR specify a specific version:
make deploy V=e9c46be
```

Kamal pulls the pre-built image from GHCR and does a zero-downtime rolling deploy to the Synology NAS.

## Important: The `service` label

The Dockerfile has `LABEL service="objectstorage"` — Kamal requires this. If you ever change the Dockerfile, make sure this label stays.

## Useful commands

| Command | What it does |
|---------|-------------|
| `make deploy` | Deploy current HEAD |
| `make deploy V=<sha>` | Deploy specific version |
| `make deploy-logs` | Tail app logs |
| `make deploy-details` | Show running containers |
| `kamal rollback <sha>` | Roll back to a previous version |
| `kamal app exec -i bash` | Shell into running container |

## Synology caveat: kamal-proxy

kamal-proxy was manually started with `--http-port 38085` because Synology doesn't grant `NET_BIND_SERVICE` to containers (can't bind port 80). If kamal-proxy ever needs to be recreated (e.g., `kamal proxy reboot`), you'll need to manually fix it:

```bash
ssh riverbank@192.168.68.54
docker stop kamal-proxy && docker rm kamal-proxy
docker run --name kamal-proxy --network kamal --detach --restart unless-stopped \
  --volume kamal-proxy-config:/home/kamal-proxy/.config/kamal-proxy \
  --volume $PWD/.kamal/proxy/apps-config:/home/kamal-proxy/.apps-config \
  --publish 38085:38085 --publish 38443:38443 --log-opt max-size=10m \
  basecamp/kamal-proxy:v0.9.0 kamal-proxy run --http-port 38085 --https-port 38443
```

Then re-deploy: `make deploy V=<sha>`

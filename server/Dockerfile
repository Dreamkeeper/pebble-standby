# Pin both the Python release and its image digest.
FROM python:3.15.1-slim@sha256:533b0b2f9b5d46fd097091cf826f27902f1d6c3bca714b6dc74551356aa97a51

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Debian security updates at build time: the pinned base image ages, and
# the CI image scan fails on fixed OS CVEs otherwise (2026-10-14: pcre2,
# openssl). Only the package index and the upgrade; no new packages.
RUN apt-get update && apt-get -y --no-install-recommends upgrade     && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
# Every package, direct and transitive, must match a hash in the lock.
# The running server needs no package installer. Remove pip and its bundled
# bootstrap copy after verifying the installed dependency set.
RUN python -m pip install --only-binary=:all: --require-hashes -r requirements.txt \
    && python -m pip check \
    && python -m pip uninstall -y pip \
    && python -c "import ensurepip, pathlib, shutil; shutil.rmtree(pathlib.Path(ensurepip.__file__).parent)"
COPY app ./app
# A build context on DSM carries owner-only ACL modes into the image: the
# code must be readable by UID 10001 (field 2026-10-14: PermissionError on
# app/__init__.py took the server down for six minutes).
RUN chmod -R a+rX /srv/app

# The data directory is a bind mount owned by the same UID on the host
# (see docs/DEPLOY-SYNOLOGY.md). The code is root-owned and read-only to
# the process.
RUN groupadd -g 10001 standby && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin standby \
    && mkdir -p /srv/data && chown 10001:10001 /srv/data
USER 10001

EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]

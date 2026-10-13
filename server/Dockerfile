# Pinned base image (harden-ci step 10): python 3.12.14-slim, published
# 2026-09-25, digest read from Docker Hub on 2026-10-13. Update the tag
# and the digest together.
FROM python:3.12.14-slim@sha256:f77ac9e44ae96ef2c90b8053ea08c31f8be030f824196b0ae4db6d462c84e51f

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /srv
COPY requirements.txt .
# Every package, direct and transitive, must match a hash in the lock.
RUN pip install --require-hashes -r requirements.txt
COPY app ./app

# The data directory is a bind mount owned by the same UID on the host
# (see docs/DEPLOY-SYNOLOGY.md). The code is root-owned and read-only to
# the process.
RUN groupadd -g 10001 standby && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin standby \
    && mkdir -p /srv/data && chown 10001:10001 /srv/data
USER 10001

EXPOSE 8080
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]

# SPDX-License-Identifier: AGPL-3.0-or-later
FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home app && mkdir /data && chown app:app /data
COPY openfuel ./openfuel
USER app
ENV OPENFUEL_DB=/data/openfuel.sqlite3
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "openfuel.api:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log", "--no-proxy-headers"]

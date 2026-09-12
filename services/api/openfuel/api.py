# SPDX-License-Identifier: AGPL-3.0-only
from __future__ import annotations
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import hmac
import json
import os
from typing import Annotated
from fastapi import FastAPI, Header, HTTPException, Query, Response, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from .audit import load_key
from .models import FuelType, PaymentType, Report
from .store import Store, Conflict

@dataclass(frozen=True)
class Settings:
    db_path: str = 'data/openfuel.sqlite3'
    write_token: str = ''
    signing_key_path: str | None = None
    source_url: str = ''

    @classmethod
    def from_env(cls):
        return cls(db_path=os.getenv('OPENFUEL_DB', 'data/openfuel.sqlite3'),
                   write_token=os.getenv('OPENFUEL_WRITE_TOKEN', ''),
                   signing_key_path=os.getenv('OPENFUEL_SIGNING_KEY'),
                   source_url=os.getenv('OPENFUEL_SOURCE_URL', ''))

class BodyLimit:
    """Bounded POST body even when Content-Length is absent. Do not log request content."""
    def __init__(self, app, limit=8192):
        self.app, self.limit = app, limit
    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope['method'] not in ('POST', 'PUT', 'PATCH'):
            return await self.app(scope, receive, send)
        chunks, total = [], 0
        while True:
            message = await receive()
            if message['type'] == 'http.disconnect':
                return
            total += len(message.get('body', b''))
            if total > self.limit:
                return await JSONResponse({'detail': 'Request too large'}, status_code=413)(scope, receive, send)
            chunks.append(message.get('body', b''))
            if not message.get('more_body', False):
                break
        supplied = False
        async def limited_receive():
            nonlocal supplied
            if not supplied:
                supplied = True
                return {'type': 'http.request', 'body': b''.join(chunks), 'more_body': False}
            return await receive()
        await self.app(scope, limited_receive, send)

def create_app(settings: Settings | None = None) -> FastAPI:
    config = settings or Settings.from_env()
    if config.write_token and len(config.write_token) < 32:
        raise ValueError('Use a random write token of at least 32 characters')

    @asynccontextmanager
    async def lifespan(app):
        app.state.store = Store(config.db_path)
        app.state.key = load_key(config.signing_key_path)
        yield

    app = FastAPI(title='OpenFuel', version='0.1.0', lifespan=lifespan,
                  description='Local prototype. Synthetic seed data. No person or trip database.',
                  license_info={'name': 'AGPL-3.0-only'}, docs_url=None, redoc_url=None)
    app.add_middleware(BodyLimit)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, error: RequestValidationError):
        # Do not reflect potentially sensitive unknown fields/values in errors or telemetry.
        return JSONResponse(status_code=422, content={'detail': 'Invalid request. See the public API schema.'})

    @app.middleware('http')
    async def headers(request, call_next):
        response = await call_next(request)
        response.headers['X-Content-Type-Options'] = 'nosniff'
        response.headers['Referrer-Policy'] = 'no-referrer'
        response.headers['Cache-Control'] = 'no-store'
        return response

    @app.get('/healthz')
    def health():
        return {'status': 'ok', 'prototype': True}

    @app.get('/v1/meta')
    def meta():
        return {'name': 'OpenFuel', 'prototype': True, 'writes_enabled': bool(config.write_token),
                'source_url': config.source_url or None,
                'source_notice': 'Operators must publish corresponding source before public deployment.',
                'privacy_notice': 'No accounts, device IDs, precise user coordinates, trip records or application access logs. Network operators still see connections.',
                'time_precision_seconds': 900, 'code_license': 'AGPL-3.0-only',
                'community_data_license': 'CC0-1.0'}

    @app.get('/v1/regions')
    def regions(request: Request):
        return {'regions': request.app.state.store.regions()}

    @app.get('/v1/stations')
    def stations(request: Request,
                 region: Annotated[str, Query(pattern=r'^[a-z0-9-]{1,64}$')] = 'demo-region',
                 fuel: FuelType = FuelType.regular, payment: PaymentType = PaymentType.standard):
        return {'region': region, 'generated_at': datetime.now(timezone.utc).isoformat(),
                'stations': request.app.state.store.stations(region, fuel.value, payment.value)}

    @app.get('/v1/stations/{station_id}/history')
    def history(station_id: str, request: Request):
        try:
            return {'observations': request.app.state.store.history(station_id)}
        except KeyError:
            raise HTTPException(404, 'Unknown station')

    @app.post('/v1/reports', status_code=201)
    def report(payload: Report, request: Request, response: Response,
               authorization: Annotated[str | None, Header()] = None):
        if not config.write_token:
            raise HTTPException(503, 'Reporting is disabled until this operator configures a closed test')
        expected = 'Bearer ' + config.write_token
        if not authorization or not hmac.compare_digest(authorization.encode(), expected.encode()):
            raise HTTPException(401, 'A closed-test write token is required')
        try:
            event, created = request.app.state.store.add_report(payload)
            if not created:
                response.status_code = 200
            return {'event': event, 'verification': 'unverified', 'created': created}
        except KeyError:
            raise HTTPException(404, 'Unknown station')
        except Conflict as error:
            raise HTTPException(409, str(error))

    @app.get('/v1/audit/export')
    def audit_export(request: Request):
        events, head = request.app.state.store.export(request.app.state.key)
        body = '\n'.join(json.dumps(item, separators=(',', ':')) for item in [*events, {'checkpoint': head}]) + '\n'
        return Response(body, media_type='application/x-ndjson', headers={
            'Content-Disposition': 'attachment; filename="openfuel-ledger.ndjson"'})

    @app.get('/v1/audit/checkpoint')
    def audit_checkpoint(request: Request):
        return request.app.state.store.export(request.app.state.key)[1]

    return app

app = create_app()

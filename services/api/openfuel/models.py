# SPDX-License-Identifier: AGPL-3.0-only
from datetime import datetime, timezone, timedelta
from enum import Enum
from pydantic import BaseModel, ConfigDict, Field, AwareDatetime, UUID4, field_validator

class FuelType(str, Enum):
    regular = 'regular'
    midgrade = 'midgrade'
    premium = 'premium'
    diesel = 'diesel'

class PaymentType(str, Enum):
    standard = 'standard'
    cash = 'cash'
    credit = 'credit'
    membership = 'membership'

class Report(BaseModel):
    model_config = ConfigDict(extra='forbid')
    report_id: UUID4
    station_id: str = Field(pattern=r'^[a-z0-9-]{1,64}$')
    fuel_type: FuelType
    payment_type: PaymentType
    price_milli: int = Field(strict=True, ge=50, le=15000)
    observed_at: AwareDatetime

    @field_validator('observed_at')
    @classmethod
    def recent(cls, value: datetime) -> datetime:
        now = datetime.now(timezone.utc)
        if value < now - timedelta(hours=48) or value > now + timedelta(minutes=5):
            raise ValueError('Observation must be within 48 hours and not over 5 minutes in the future')
        return value.astimezone(timezone.utc)

def bucket(value: datetime) -> str:
    value = value.astimezone(timezone.utc)
    return value.replace(minute=(value.minute // 15) * 15, second=0, microsecond=0).isoformat().replace('+00:00', 'Z')

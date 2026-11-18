-- ADR-0002: every run belongs to a tenant, taken from the validated JWT claim `tenant_id` (or the configured legacy
-- tenant for the shared-token webhook). Existing rows came from the shared-token webhook: tenant 'legacy'.

ALTER TABLE triage_run ADD COLUMN tenant_id VARCHAR(64);
UPDATE triage_run SET tenant_id = 'legacy';
ALTER TABLE triage_run ALTER COLUMN tenant_id SET NOT NULL;

-- One incident is one run *per tenant*: two tenants may legitimately see the same fingerprint and start time.
ALTER TABLE triage_run DROP CONSTRAINT uq_triage_incident;
ALTER TABLE triage_run ADD CONSTRAINT uq_triage_incident UNIQUE (tenant_id, fingerprint, starts_at);

-- Every read is tenant-scoped, newest first.
CREATE INDEX idx_triage_run_tenant_created ON triage_run (tenant_id, created_at DESC);

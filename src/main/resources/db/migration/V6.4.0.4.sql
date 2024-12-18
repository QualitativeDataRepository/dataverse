-- Add this boolean flag to accommodate a new harvesting client feature
-- QDR - incremented file number
ALTER TABLE harvestingclient ADD COLUMN IF NOT EXISTS useOaiIdAsPid BOOLEAN DEFAULT FALSE;

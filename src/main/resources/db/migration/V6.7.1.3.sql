-- Insert records into the curationstatus table for datasetversions that don't already have an entry
-- This is idempotent - running it multiple times will not create duplicate entries
INSERT INTO curationstatus (id, createtime, label, authenticateduser_id, datasetversion_id)
SELECT 
    nextval('curationstatus_id_seq'), -- Generate new IDs using the sequence
    dv.createtime, -- Use the datasetversion's creation date
    NULL, -- Null label as requested
    NULL, -- Null authenticateduser_id as requested
    dv.id -- Use the datasetversion's ID
FROM 
    datasetversion dv
WHERE 
    NOT EXISTS (
        -- Skip datasetversions that already have an entry in curationstatus with the same version and createtime or that have labels that predate the history functionality
        SELECT 1 
        FROM curationstatus cs 
        WHERE cs.datasetversion_id = dv.id AND (cs.createtime = dv.createtime or cs.createtime IS NULL)
    );
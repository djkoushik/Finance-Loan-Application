-- Areas (colony/street) an administrator maintains in the Config Center. Debtors pick one of these.
CREATE TABLE areas (
    area_name  varchar(100) PRIMARY KEY,
    is_active  boolean NOT NULL DEFAULT true
);

-- the areas already in use
INSERT INTO areas (area_name)
SELECT DISTINCT trim(street) FROM debtors WHERE trim(street) <> '';

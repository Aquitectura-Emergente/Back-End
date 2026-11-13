-- Lecturas saturadas: el campo fue de al menos el valor informado. Se usan como
-- mínimo y la jornada queda marcada como afectada por lecturas fuera de rango.
ALTER TABLE personal_exposure_readings ADD COLUMN saturated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE personal_exposure_sessions ADD COLUMN out_of_range_readings INT NOT NULL DEFAULT 0;

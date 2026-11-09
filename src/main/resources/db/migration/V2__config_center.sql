-- Config Center: values an administrator can change from the application itself.

-- Key/value overrides: app.name, profile.name, profile.role_display, page.<route> (page name shown in menu and title)
CREATE TABLE app_settings (
    setting_key    varchar(100) PRIMARY KEY,
    setting_value  varchar(200) NOT NULL,
    updated_by     varchar(50),
    updated_at     timestamp NOT NULL DEFAULT now()
);

-- Internal value (stored in the data tables) -> display value (shown to people).
-- Used for lists that have no table of their own (gender). Notes and roles keep their existing tables
-- (note_types, roles) and are edited through the same screen.
CREATE TABLE dropdown_values (
    dropdown_key    varchar(30)  NOT NULL,
    internal_value  varchar(30)  NOT NULL,
    display_value   varchar(100) NOT NULL,
    is_active       boolean      NOT NULL DEFAULT true,
    PRIMARY KEY (dropdown_key, internal_value)
);

INSERT INTO app_settings (setting_key, setting_value) VALUES
    ('app.name', 'SRI RAJA RAJESHWARI'),
    ('profile.role_display', 'admin');   -- admin = show role only for administrators, all, none

INSERT INTO dropdown_values (dropdown_key, internal_value, display_value) VALUES
    ('gender', 'M', 'Male'),
    ('gender', 'F', 'Female');

-- V13: Add company address and contact settings if not already present
INSERT INTO settings (key_name, value, description)
VALUES ('company_address', '116 Santan St., Fortune, Marikina City', 'Company address')
ON CONFLICT (key_name) DO NOTHING;

INSERT INTO settings (key_name, value, description)
VALUES ('company_contact', '+63 966 846 9993', 'Company contact number')
ON CONFLICT (key_name) DO NOTHING;

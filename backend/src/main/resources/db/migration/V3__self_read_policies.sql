-- E02-B14: a signed-in user without a tenant context (/me/*, tenant switcher) may read the rows
-- that describe their OWN memberships. Nothing here widens access to other users' rows.

-- Own extra permissions (membership_permissions previously required a tenant context).
CREATE POLICY self_membership_permissions ON app.membership_permissions FOR SELECT TO app_runtime
  USING (EXISTS (SELECT 1 FROM app.organization_memberships m
                 WHERE m.id = membership_permissions.membership_id AND m.user_id = app.current_user_id()));

-- Own employee profile (employeeId in the membership list).
CREATE POLICY self_employee ON app.employees FOR SELECT TO app_runtime
  USING (EXISTS (SELECT 1 FROM app.organization_memberships m
                 WHERE m.id = employees.membership_id AND m.user_id = app.current_user_id()));

-- Invited or suspended members may see the organization's public reference (name/slug) so the
-- switcher can explain the state; revoked members still see nothing.
DROP POLICY organizations_access ON app.organizations;
CREATE POLICY organizations_access ON app.organizations FOR SELECT TO app_runtime
  USING (
    id = app.current_organization_id()
    OR app.platform_mode()
    OR EXISTS (SELECT 1 FROM app.organization_memberships m
               WHERE m.organization_id = organizations.id AND m.user_id = app.current_user_id() AND m.status <> 'REVOKED')
  );

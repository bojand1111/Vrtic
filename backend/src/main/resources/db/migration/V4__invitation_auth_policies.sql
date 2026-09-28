-- E02-B04/B05/B09: the authentication module resolves invitation and reset/verification tokens
-- BEFORE any tenant context exists (the token itself is the capability). It therefore needs
-- auth-mode access to invitations, the invited organization's public reference and the
-- membership row it creates on acceptance. Everything stays bounded by app.auth_mode(), which
-- only the auth module sets, and every query in it is keyed by the token hash or the user id.

CREATE POLICY invitations_auth ON app.invitations FOR SELECT TO app_runtime USING (app.auth_mode());
CREATE POLICY invitations_auth_accept ON app.invitations FOR UPDATE TO app_runtime
  USING (app.auth_mode()) WITH CHECK (app.auth_mode());

DROP POLICY organizations_access ON app.organizations;
CREATE POLICY organizations_access ON app.organizations FOR SELECT TO app_runtime
  USING (
    id = app.current_organization_id()
    OR app.platform_mode()
    OR app.auth_mode()
    OR EXISTS (SELECT 1 FROM app.organization_memberships m
               WHERE m.organization_id = organizations.id AND m.user_id = app.current_user_id() AND m.status <> 'REVOKED')
  );

CREATE POLICY memberships_auth_accept ON app.organization_memberships FOR ALL TO app_runtime
  USING (app.auth_mode()) WITH CHECK (app.auth_mode());

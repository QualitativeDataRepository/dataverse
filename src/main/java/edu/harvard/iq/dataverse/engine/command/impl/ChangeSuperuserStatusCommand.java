/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.harvard.iq.dataverse.engine.command.impl;

import java.util.List;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.authorization.Permission;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.engine.command.AbstractVoidCommand;
import edu.harvard.iq.dataverse.engine.command.CommandContext;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.RequiredPermissions;
import edu.harvard.iq.dataverse.engine.command.exception.CommandException;
import edu.harvard.iq.dataverse.engine.command.exception.PermissionException;
import edu.harvard.iq.dataverse.pidproviders.PidProvider;
import edu.harvard.iq.dataverse.settings.FeatureFlags;
import jakarta.json.JsonObject;

/**
 *
 * @author Leonid Andreev
 */
// the permission annotation is open, since this is a superuser-only command - 
// and that's enforced in the command body:
@RequiredPermissions({})
public class ChangeSuperuserStatusCommand extends AbstractVoidCommand  {

    private final AuthenticatedUser targetUser;
    private final boolean newStatus;
    
    public ChangeSuperuserStatusCommand (AuthenticatedUser targetUser, boolean newStatus, DataverseRequest aRequest) {
        super(aRequest, (Dataset)null);
        this.newStatus = newStatus;
        this.targetUser = targetUser;
    }

    @Override
    protected void executeImpl(CommandContext ctxt) throws CommandException {

        if (!(getUser() instanceof AuthenticatedUser) || !getUser().isSuperuser()) {
            throw new PermissionException("Change Superuser status command can only be called by superusers.",
                    this, null, null);
        }

        if (newStatus && targetUser.isDeactivated()) {
            throw new CommandException("User " + targetUser.getIdentifier() + " has been deactivated and cannot become a superuser.", this);
        }
        List<AuthenticatedUser> superusers = null;
        if (FeatureFlags.INFORM_SUPERUSERS_OF_CHANGES.enabled()) {
            // Get the list of existing superusers
            superusers = ctxt.authentication().findSuperUsers();
        }
        try {
            targetUser.setSuperuser(newStatus);
            ctxt.em().merge(targetUser);
            ctxt.em().flush();
            
            // Check if the INFORM_SUPERUSERS_OF_CHANGES feature flag is set
            if (superusers != null) {
                // Prepare the email message
                String subject = "Superuser Status Change";
                String message = "User " + targetUser.getIdentifier() + " has had superuser status  " + (newStatus? "granted":"revoked" + " by " + getUser().getIdentifier() + " ." );
                
                // Send email to all superusers (including the person who's status is revoked - important if legit superusers are being removed.)
                for (AuthenticatedUser superuser : superusers) {
                    ctxt.mail().sendSystemEmail(superuser.getEmail(), subject, message);
                }
            }
        } catch (Exception e) {
            throw new CommandException("Failed to change the superuser status of user "+targetUser.getIdentifier() + "to" + newStatus, this);
        }
    }
}

package io.quarkiverse.playpen.operator;

import static io.javaoperatorsdk.operator.api.reconciler.Constants.WATCH_ALL_NAMESPACES;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

@ControllerConfiguration(namespaces = WATCH_ALL_NAMESPACES, name = "devspaceconfig")
public class DevspaceConfigReconciler implements Reconciler<DevspaceConfig> {
    @Override
    public UpdateControl<DevspaceConfig> reconcile(DevspaceConfig resource, Context<DevspaceConfig> context)
            throws Exception {

        return UpdateControl.patchStatus(resource);
    }
}

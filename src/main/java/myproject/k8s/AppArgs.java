package myproject.k8s;

import com.pulumi.core.Output;
import com.pulumi.kubernetes.core_v1.inputs.ContainerPortArgs;
import com.pulumi.kubernetes.core_v1.inputs.EnvVarArgs;
import com.pulumi.resources.ResourceArgs;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Builder
@Getter
public class AppArgs extends ResourceArgs {
    private Output<String> namespace;
    private Output<String> jarUrl;
    private EnvVarArgs envVars;
    private ContainerPortArgs portArgs;
    private Map<String, String> labels;
}

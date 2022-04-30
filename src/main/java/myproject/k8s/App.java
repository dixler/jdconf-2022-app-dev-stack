package myproject.k8s;

import com.pulumi.core.Output;
import com.pulumi.kubernetes.apps_v1.Deployment;
import com.pulumi.kubernetes.apps_v1.DeploymentArgs;
import com.pulumi.kubernetes.apps_v1.inputs.DeploymentSpecArgs;
import com.pulumi.kubernetes.core_v1.Service;
import com.pulumi.kubernetes.core_v1.ServiceArgs;
import com.pulumi.kubernetes.core_v1.enums.ServiceSpecType;
import com.pulumi.kubernetes.core_v1.inputs.*;
import com.pulumi.kubernetes.meta_v1.inputs.LabelSelectorArgs;
import com.pulumi.kubernetes.meta_v1.inputs.ObjectMetaArgs;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.nio.file.Path;
import java.util.List;

public class App extends ComponentResource {
  public Output<String> url;

  public App(String name, AppArgs args, ComponentResourceOptions options) {
    // https://pulumi.com/docs/intro/concepts/resources/components/
    super("myproject:k8s:App", name, args, options);

    final var targetDir = "/var/run/secrets/java";
    final var targetJar = Path.of(targetDir, "app.jar").toString();

    final var opts = CustomResourceOptions.builder()
            .parent(this)
            .build();

    // Configure Metadata args
    final var metadata = ObjectMetaArgs.builder()
            .namespace(args.getNamespace())
            .labels(args.getLabels())
            .build();

    final var volume = VolumeArgs.builder()
            .name("jar-volume")
            .emptyDir(EmptyDirVolumeSourceArgs.Empty)
            .build();

    final var volumeMount = VolumeMountArgs.builder()
            .name(volume.name())
            .mountPath(targetDir)
            .build();

    final var initContainer = ContainerArgs.builder()
            .name("init")
            .image("bash")
            .command(args.getJarUrl()
                    .applyValue(jarUrl -> List.of("wget", jarUrl, "-O", targetJar)))
            .volumeMounts(volumeMount)
            .build();

    final var appContainer = ContainerArgs.builder()
            .name("app")
            .image("openjdk")
            .command("java", "-jar", targetJar)
            .env(args.getEnvVars())
            .ports(args.getPortArgs())
            .volumeMounts(volumeMount)
            .build();

    final var deployment = new Deployment(
            "deployment",
            DeploymentArgs.builder()
                .metadata(metadata)
                .spec(DeploymentSpecArgs.builder()
                        .replicas(1)
                        .selector(LabelSelectorArgs.builder()
                                .matchLabels(args.getLabels())
                                .build())
                        .template(PodTemplateSpecArgs.builder()
                                .metadata(metadata)
                                .spec(PodSpecArgs.builder()
                                        .volumes(volume)
                                        .initContainers(initContainer)
                                        .containers(appContainer)
                                        .build())
                                .build())
                        .build())
                .build(),
            opts);

    final var service =
        new Service(
            "app-svc",
            ServiceArgs.builder()
                .metadata(metadata)
                .spec(
                    ServiceSpecArgs.builder()
                        .type(ServiceSpecType.LoadBalancer)
                        .ports(ServicePortArgs.builder().port(80).targetPort("http").build())
                        .selector(args.getLabels())
                        .build())
                .build(),
            opts);
    this.url =
        service
            .status()
            .applyValue(status -> status.get().loadBalancer().get().ingress().get(0).ip().get());
  }
}

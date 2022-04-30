package myproject;

import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.EnvironmentCredentialBuilder;
import com.pulumi.Pulumi;
import com.pulumi.asset.FileAsset;
import com.pulumi.azurenative.storage.*;
import com.pulumi.azurenative.storage.enums.Kind;
import com.pulumi.azurenative.storage.enums.PublicAccess;
import com.pulumi.azurenative.storage.enums.SkuName;
import com.pulumi.azurenative.storage.inputs.ActiveDirectoryPropertiesArgs;
import com.pulumi.azurenative.storage.inputs.AzureFilesIdentityBasedAuthenticationArgs;
import com.pulumi.azurenative.storage.inputs.SkuArgs;
import com.pulumi.core.Output;
import com.pulumi.kubernetes.ProviderArgs;
import com.pulumi.kubernetes.apps_v1.Deployment;
import com.pulumi.kubernetes.apps_v1.DeploymentArgs;
import com.pulumi.kubernetes.apps_v1.inputs.DeploymentSpecArgs;
import com.pulumi.kubernetes.core_v1.Service;
import com.pulumi.kubernetes.core_v1.ServiceArgs;
import com.pulumi.kubernetes.core_v1.enums.ServiceSpecType;
import com.pulumi.kubernetes.core_v1.inputs.*;
import com.pulumi.kubernetes.meta_v1.inputs.LabelSelectorArgs;
import com.pulumi.kubernetes.meta_v1.inputs.ObjectMetaArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.StackReference;

import com.pulumi.kubernetes.Provider;
import com.azure.data.appconfiguration.ConfigurationClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class PulumiStack {
  public static void main(String[] args) {
    final var CONNECTION_STRING_VAR = "APP_CONFIGURATION_CONNECTION_STRING";
    final var TARGET_DIR = "/var/run/secrets/java";
    final var TARGET_JAR = Path.of(TARGET_DIR, "app.jar").toString();

    Pulumi.run(
        ctx -> {
          final var config = ctx.config();
          final var configConnectionString = config.require("configConnectionString");
          final var platformStack = config.require("platformStack");
          final var ref = new StackReference(platformStack);

          final var kubeconfig = ref
                  .requireOutput("kubeconfig")
                  .applyValue(String::valueOf);
          final var namespace = ref
                  .requireOutput("namespace")
                  .applyValue(String::valueOf);
          final var configEndpoint = ref
                  .requireOutput("configStore")
                  .applyValue(String::valueOf);


          final var opts =
              CustomResourceOptions.builder()
                  .provider(
                      new Provider(
                          "kubernetes-provider",
                          ProviderArgs.builder().kubeconfig(kubeconfig).build()))
                  .build();

          final var sa =
              new StorageAccount(
                  "myblob",
                  StorageAccountArgs.builder()
                      .kind(Kind.StorageV2)
                      .sku(SkuArgs.builder().name(SkuName.Standard_LRS).build())
                      .resourceGroupName("mspulumi")
                      .build());
          final var container =
              new BlobContainer(
                  "myblob",
                  BlobContainerArgs.builder()
                      .accountName(sa.name())
                      .containerName("java")
                      .publicAccess(PublicAccess.Blob)
                      .resourceGroupName("mspulumi")
                      .build());

          final var jar =
              new Blob(
                  "application-jar",
                  BlobArgs.builder()
                      .blobName("app.jar")
                      .accountName(sa.name())
                      .containerName(container.name())
                      .resourceGroupName("mspulumi")
                      // TODO move this
                      .source(
                          new FileAsset(
                              "/home/demo/Documents/jdconf-2022/devopsforjavashops-testfeatureflags-pulumi/target/demo-0.0.1-SNAPSHOT.jar"))
                      .build());

          final var appLabels =
              Map.of(
                  "costcenter", "1234567890",
                  "contact", "ops",
                  "appname", "devopsjavashops");

          // Configure Metadata args
          final var metadata =
              ObjectMetaArgs.builder().namespace(namespace).labels(appLabels).build();

          final var deployment =
              new Deployment(
                  "deployment",
                  DeploymentArgs.builder()
                      .metadata(metadata)
                      .spec(
                          DeploymentSpecArgs.builder()
                              .replicas(1)
                              .selector(LabelSelectorArgs.builder().matchLabels(appLabels).build())
                              .template(
                                  PodTemplateSpecArgs.builder()
                                      .metadata(metadata)
                                      .spec(
                                          PodSpecArgs.builder()
                                              .volumes(
                                                  VolumeArgs.builder()
                                                      .name("jar-volume")
                                                      .emptyDir(EmptyDirVolumeSourceArgs.Empty)
                                                      .build())
                                              .initContainers(
                                                  ContainerArgs.builder()
                                                      .name("init")
                                                      .image("bash")
                                                      .command(
                                                          jar.url()
                                                          .applyValue(
                                                              jarUrl -> List.of( "wget", jarUrl, "-O", TARGET_JAR)))
                                                      .volumeMounts(
                                                          VolumeMountArgs.builder()
                                                              .name("jar-volume")
                                                              .mountPath(TARGET_DIR)
                                                              .build())
                                                      .build())
                                              .containers(
                                                  ContainerArgs.builder()
                                                      .name("app")
                                                      .image("openjdk")
                                                      .command("java", "-jar", TARGET_JAR)
                                                      .env(
                                                          EnvVarArgs.builder()
                                                              .name("APP_CONFIGURATION_CONNECTION_STRING")
                                                              .value(configConnectionString)
                                                              .build())
                                                      .volumeMounts(
                                                          VolumeMountArgs.builder()
                                                              .name("jar-volume")
                                                              .mountPath(TARGET_DIR)
                                                              .build())
                                                      .ports(
                                                          ContainerPortArgs.builder()
                                                              .name("http")
                                                              .containerPort(8080)
                                                              .build())
                                                      .build())
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
                              .type(Output.ofRight(ServiceSpecType.LoadBalancer))
                              .ports(
                                  ServicePortArgs.builder()
                                      .port(80)
                                      .targetPort(Output.ofRight("http"))
                                      .build())
                              .selector(appLabels)
                              .build())
                      .build(),
                  opts);
          ctx.export("service", service.status().applyValue(status -> status.get().loadBalancer().get().ingress().get(0).ip()));
          return ctx.exports();
        });
  }
}

package myproject;

import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.EnvironmentCredentialBuilder;
import com.pulumi.Context;
import com.pulumi.Exports;
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
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.StackReference;

import com.pulumi.kubernetes.Provider;
import com.azure.data.appconfiguration.ConfigurationClient;
import myproject.k8s.App;
import myproject.k8s.AppArgs;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class PulumiStack {
    public static void main(String[] args) {
        Pulumi.run(PulumiStack::stack);
    }
    public static Exports stack(Context ctx) {
        //https://www.pulumi.com/docs/intro/concepts/config/
        final var config = ctx.config();
        final var configConnectionString = config.require("configConnectionString");
        final var platformStack = config.require("platformStack");

        // https://www.pulumi.com/docs/intro/concepts/stack/#stackreferences
        final var ref = new StackReference(platformStack);
        final var kubeconfig = ref.requireOutput("kubeconfig").applyValue(String::valueOf);
        final var namespace = ref.requireOutput("namespace").applyValue(String::valueOf);
        final var configEndpoint = ref.requireOutput("configStore").applyValue(String::valueOf);

        // https://www.pulumi.com/docs/intro/concepts/resources/
        final var sa = new StorageAccount(
            // https://www.pulumi.com/docs/intro/concepts/resources/names/
            "myblob",
            StorageAccountArgs.builder()
                .kind(Kind.StorageV2)
                .sku(SkuArgs.builder().name(SkuName.Standard_LRS).build())
                .resourceGroupName("mspulumi")
                .build());

        final var container = new BlobContainer(
            "myblob",
            BlobContainerArgs.builder()
                .accountName(sa.name())
                .containerName("java")
                // WARNING: these files are publicly readable.
                .publicAccess(PublicAccess.Blob)
                .resourceGroupName("mspulumi")
                .build());

        final var jar = new Blob(
                "application-jar",
                BlobArgs.builder()
                    .blobName("app.jar")
                    .accountName(sa.name())
                    .containerName(container.name())
                    .resourceGroupName("mspulumi")
                    // TODO move this
                    .source(new FileAsset(
                        "/home/demo/Documents/jdconf-2022/devopsforjavashops-testfeatureflags-pulumi/target/demo-0.0.1-SNAPSHOT.jar"))
                    .build());

        final var app = new App(
            "deployment",
            AppArgs.builder()
                .jarUrl(jar.url())
                .namespace(namespace)
                .envVars(
                    EnvVarArgs.builder()
                        .name("APP_CONFIGURATION_CONNECTION_STRING")
                        .value(configConnectionString)
                        .build())
                .portArgs(
                    ContainerPortArgs.builder().name("http").containerPort(8080).build())
                .labels(
                    Map.of(
                        "costcenter", "1234567890",
                        "contact", "ops",
                        "appname", "devopsjavashops"))
                .build(),
            // https://www.pulumi.com/docs/intro/concepts/resources/options/
            ComponentResourceOptions.builder()
                .provider(new Provider(
                    "kubernetes-provider",
                    ProviderArgs.builder()
                        .kubeconfig(kubeconfig)
                        .build()))
                .build());
        ctx.export("service", app.url);
        return ctx.exports();
    }
}

package myproject;

import com.azure.core.exception.ResourceExistsException;
import com.azure.core.util.ClientOptions;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.FeatureFlagConfigurationSetting;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.appconfiguration.AppConfigurationManager;
import com.pulumi.Context;
import com.pulumi.Exports;
import com.pulumi.Pulumi;
import com.pulumi.asset.FileAsset;
import com.pulumi.azurenative.appconfiguration.ConfigurationStore;
import com.pulumi.azurenative.appconfiguration.KeyValue;
import com.pulumi.azurenative.appconfiguration.KeyValueArgs;
import com.pulumi.azurenative.storage.*;
import com.pulumi.azurenative.storage.enums.Kind;
import com.pulumi.azurenative.storage.enums.PublicAccess;
import com.pulumi.azurenative.storage.enums.SkuName;
import com.pulumi.azurenative.storage.inputs.SkuArgs;
import com.pulumi.core.Output;
import com.pulumi.kubernetes.ProviderArgs;
import com.pulumi.kubernetes.core_v1.inputs.*;
import com.pulumi.resources.ComponentResourceOptions;
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
        final var platformStack = config.require("platformStack");

        // https://www.pulumi.com/docs/intro/concepts/stack/#stackreferences
        final var ref = new StackReference(platformStack);
        final var kubeconfig = ref.requireOutput("kubeconfig").applyValue(String::valueOf);
        final var namespace = ref.requireOutput("namespace").applyValue(String::valueOf);
        final var configStoreName = ref.requireOutput("configStore").applyValue(String::valueOf);
        final var configConnectionString = ref.requireOutput("configStoreConnectionString").applyValue(String::valueOf);

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

        configConnectionString.applyValue(conString -> {
            final var client = new ConfigurationClientBuilder()
                .connectionString(conString)
                    .buildClient();
            try {
                client.addConfigurationSetting(new FeatureFlagConfigurationSetting("Beta", false));
            } catch (ResourceExistsException e) { }
            return conString;
        });

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
        ctx.export("service", Output.format("http://%s/welcome", app.url));
        return ctx.exports();
    }
}

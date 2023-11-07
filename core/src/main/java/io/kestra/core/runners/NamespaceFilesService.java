package io.kestra.core.runners;

import io.kestra.core.models.tasks.NamespaceFiles;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.FileAttributes;
import io.kestra.core.storages.StorageInterface;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.kestra.core.utils.Rethrow.*;

@Singleton
@Slf4j
public class NamespaceFilesService {
    @Inject
    private StorageInterface storageInterface;

    public List<URI> inject(RunContext runContext, String tenantId, String namespace, Path basePath, Object namespaceFiles) throws Exception {
        if (namespaceFiles instanceof Boolean && !((Boolean) namespaceFiles)) {
            return null;
        }

        List<URI> list = new ArrayList<>();
        list.addAll(recursiveList(tenantId, namespace, null));

        if (namespaceFiles instanceof Boolean && ((Boolean) namespaceFiles)) {
            copy(tenantId, namespace, basePath, list);
            return list;
        }

        NamespaceFiles filter = JacksonMapper.cast(namespaceFiles, NamespaceFiles.class);

        list = list
            .stream()
            .filter(throwPredicate(f -> {
                var file = f.getPath();

                if (filter.getExclude() != null) {
                    boolean b = match(runContext.render(filter.getExclude()), file);

                    if (b) {
                        return false;
                    }
                }

                if (filter.getInclude() != null) {
                    boolean b = match(filter.getInclude(), file);

                    if (!b) {
                        return false;
                    }
                }

                return true;
            }))
            .collect(Collectors.toList());

        copy(tenantId, namespace, basePath, list);

        return list;
    }

    private URI uri(String namespace, @Nullable URI path) {
        return URI.create(storageInterface.namespaceFilePrefix(namespace) + Optional.ofNullable(path)
            .map(URI::getPath)
            .orElse("")
        );
    }

    private List<URI> recursiveList(String tenantId, String namespace, @Nullable URI path) throws IOException {
        URI uri = uri(namespace, path);

        List<URI> result = new ArrayList<>();
        List<FileAttributes> list = storageInterface.list(tenantId, uri);

        for (var file: list) {
            URI current = URI.create((path != null ? path.getPath() : "") +  "/" + file.getFileName());

            if (file.getType() == FileAttributes.FileType.Directory) {
                result.addAll(this.recursiveList(tenantId, namespace, current));
            } else {
                result.add(current);
            }
        }

        return result;
    }

    private static boolean match(List<String> patterns, String file) {
        return patterns
            .stream()
            .anyMatch(s -> FileSystems
                .getDefault()
                .getPathMatcher("glob:" + s)
                .matches(Paths.get(file))
            );
    }

    private void copy(String tenantId, String namespace, Path basePath, List<URI> files) throws IOException {
        files
            .parallelStream()
            .forEach(throwConsumer(f -> {
                InputStream inputStream = storageInterface.get(tenantId, uri(namespace, f));
                Path destination = Paths.get(basePath.toString(), f.getPath());

                if (!destination.getParent().toFile().exists()) {
                    destination.getParent().toFile().mkdirs();
                }

                Files.copy(inputStream, destination);
            }));
    }
}

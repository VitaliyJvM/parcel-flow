package ca.vm.parcelflow.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope.
 *
 * <p>Spring Data's {@code Page} is not serialized directly: its JSON shape is an implementation
 * detail that has changed between Spring Data versions, and serializing {@code PageImpl} is
 * explicitly discouraged. Owning the envelope keeps the published API contract ours.
 */
@Schema(description = "A page of results")
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}

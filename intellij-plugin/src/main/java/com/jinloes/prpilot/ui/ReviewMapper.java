package com.jinloes.prpilot.ui;

import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.ReviewResult;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * Compile-time-verified mapping from core model types to webview DTO records. MapStruct generates
 * the implementation at build time based on matching getter/constructor-component names, so adding
 * a field to {@link ReviewResult} or {@link LineComment} produces a compile error here rather than
 * a silent runtime omission.
 */
@Mapper
interface ReviewMapper {

    ReviewMapper INSTANCE = Mappers.getMapper(ReviewMapper.class);

    ReviewResultDto toDto(ReviewResult result);

    LineCommentDto toDto(LineComment comment);
}

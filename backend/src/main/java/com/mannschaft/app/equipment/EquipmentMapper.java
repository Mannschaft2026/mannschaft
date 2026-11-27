package com.mannschaft.app.equipment;

import com.mannschaft.app.equipment.dto.AssignmentResponse;
import com.mannschaft.app.equipment.dto.EquipmentItemResponse;
import com.mannschaft.app.equipment.dto.QrCodeResponse;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/**
 * 備品管理機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface EquipmentMapper {

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "availableQuantity", expression = "java(entity.getAvailableQuantity())")
    @Mapping(target = "imageUrl", source = "s3Key")
    EquipmentItemResponse toItemResponse(EquipmentItemEntity entity);

    /**
     * F10.9 サポート閲覧（代理）中の一覧・詳細用: QR コード識別子（読み取ると貸出・返却の画面に入れる値）だけを外す。
     * 他の項目は通常と同じに返す（チャートの署名 URL のマスクと同じ方式）。
     */
    @Named("withoutQrCode")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "availableQuantity", expression = "java(entity.getAvailableQuantity())")
    @Mapping(target = "imageUrl", source = "s3Key")
    @Mapping(target = "qrCode", ignore = true)
    EquipmentItemResponse toItemResponseWithoutQrCode(EquipmentItemEntity entity);

    List<EquipmentItemResponse> toItemResponseList(List<EquipmentItemEntity> entities);

    @Mapping(target = "assignmentId", source = "id")
    @Mapping(target = "equipmentName", ignore = true)
    @Mapping(target = "assignedToDisplayName", ignore = true)
    AssignmentResponse toAssignmentResponse(EquipmentAssignmentEntity entity);

    List<AssignmentResponse> toAssignmentResponseList(List<EquipmentAssignmentEntity> entities);

    @Mapping(target = "qrUrl", ignore = true)
    QrCodeResponse toQrCodeResponse(EquipmentItemEntity entity);

    List<QrCodeResponse> toQrCodeResponseList(List<EquipmentItemEntity> entities);
}

package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.VehicleType;
import com.mannschaft.app.parking.dto.CreateVehicleRequest;
import com.mannschaft.app.parking.dto.UpdateVehicleRequest;
import com.mannschaft.app.parking.dto.VehicleResponse;
import com.mannschaft.app.parking.entity.RegisteredVehicleEntity;
import com.mannschaft.app.parking.repository.RegisteredVehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 登録車両サービス。車両のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegisteredVehicleService {

    private final RegisteredVehicleRepository vehicleRepository;
    private final EncryptionService encryptionService;

    /**
     * ユーザーの車両一覧を取得する。
     */
    public List<VehicleResponse> listByUser(Long userId) {
        List<RegisteredVehicleEntity> vehicles = vehicleRepository.findByUserId(userId);
        return vehicles.stream().map(this::toResponseWithDecrypt).toList();
    }

    /**
     * 指定ユーザー本人の車両であることを要求する。
     *
     * <p>他人の車両・存在しない車両は同一の {@code VEHICLE_NOT_FOUND}（404）で拒否し、
     * 車両 ID の存在オラクルを作らない（CMP-260930-0233）。</p>
     */
    public void requireOwnedVehicle(Long userId, Long vehicleId) {
        if (vehicleId == null || vehicleRepository.findByIdAndUserId(vehicleId, userId).isEmpty()) {
            throw new BusinessException(ParkingErrorCode.VEHICLE_NOT_FOUND);
        }
    }

    /**
     * 車両を登録する。
     */
    @Transactional
    public VehicleResponse create(Long userId, CreateVehicleRequest request) {
        String hash = hashPlateNumber(request.getPlateNumber());
        vehicleRepository.findByPlateNumberHash(hash).ifPresent(v -> {
            throw new BusinessException(ParkingErrorCode.PLATE_NUMBER_DUPLICATE);
        });

        RegisteredVehicleEntity entity = RegisteredVehicleEntity.builder()
                .userId(userId)
                .vehicleType(EnumInputParser.parse(VehicleType.class, request.getVehicleType(), "vehicleType"))
                .plateNumber(encryptionService.encryptBytes(
                        request.getPlateNumber().getBytes(StandardCharsets.UTF_8)))
                .plateNumberHash(hash)
                .nickname(request.getNickname())
                .build();
        RegisteredVehicleEntity saved = vehicleRepository.save(entity);
        log.info("車両登録: userId={}, vehicleType={}", userId, request.getVehicleType());
        return toResponseWithDecrypt(saved);
    }

    /**
     * 車両を更新する。
     */
    @Transactional
    public VehicleResponse update(Long userId, Long id, UpdateVehicleRequest request) {
        RegisteredVehicleEntity entity = vehicleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VEHICLE_NOT_FOUND));
        String hash = hashPlateNumber(request.getPlateNumber());
        vehicleRepository.findByPlateNumberHash(hash).ifPresent(v -> {
            if (!v.getId().equals(id)) {
                throw new BusinessException(ParkingErrorCode.PLATE_NUMBER_DUPLICATE);
            }
        });

        entity.update(EnumInputParser.parse(VehicleType.class, request.getVehicleType(), "vehicleType"),
                encryptionService.encryptBytes(
                        request.getPlateNumber().getBytes(StandardCharsets.UTF_8)),
                hash, request.getNickname());
        RegisteredVehicleEntity saved = vehicleRepository.save(entity);
        log.info("車両更新: id={}", id);
        return toResponseWithDecrypt(saved);
    }

    /**
     * 車両を論理削除する。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        RegisteredVehicleEntity entity = vehicleRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.VEHICLE_NOT_FOUND));
        entity.softDelete();
        vehicleRepository.save(entity);
        log.info("車両削除: id={}", id);
    }

    /**
     * ナンバープレートのHMACハッシュを生成する。
     */
    private String hashPlateNumber(String plateNumber) {
        return encryptionService.hmac(plateNumber);
    }

    /**
     * エンティティをレスポンスに変換し、ナンバープレートを復号する。
     */
    private VehicleResponse toResponseWithDecrypt(RegisteredVehicleEntity entity) {
        String decryptedPlate = null;
        if (entity.getPlateNumber() != null) {
            byte[] decrypted = encryptionService.decryptBytes(entity.getPlateNumber());
            decryptedPlate = new String(decrypted, StandardCharsets.UTF_8);
        }
        return new VehicleResponse(
                entity.getId(), entity.getUserId(),
                entity.getVehicleType().name(), decryptedPlate,
                entity.getNickname(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}

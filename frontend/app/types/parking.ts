import type { components } from '~/types/generated'

/** 区画の状態（BE: SpaceStatus）。 */
export type ParkingSpaceStatus = 'VACANT' | 'OCCUPIED' | 'MAINTENANCE'
/** 申請受付状態（BE: ApplicationStatus）。 */
export type ParkingApplicationAcceptStatus = 'NOT_ACCEPTING' | 'ACCEPTING' | 'LOTTERY_CLOSED'
/** 割当方式（BE: AllocationMethod）。 */
export type ParkingAllocationMethod = 'FIRST_COME' | 'LOTTERY'
/** 区画種別（BE: SpaceType）。 */
export type ParkingSpaceType =
  | 'INDOOR'
  | 'OUTDOOR'
  | 'ACCESSIBLE'
  | 'MOTORCYCLE'
  | 'BICYCLE'
  | 'VISITOR'
  | 'OTHER'
/** 申請の状態（BE: ParkingApplicationStatus）。 */
export type ParkingApplicationStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'LOTTERY_PENDING'

/** 区画（生成型 SpaceResponse。列挙は文字列で届くため上の union で絞って使う）。 */
export type ParkingSpaceResponse = components['schemas']['SpaceResponse']

/** 車両（生成型 VehicleResponse）。 */
export type VehicleResponse = components['schemas']['VehicleResponse']

/** 申請（生成型 ApplicationResponse）。 */
export type ApplicationResponse = components['schemas']['ApplicationResponse']

/** 一覧のページ情報（BE の PageMeta。page は 0 始まり）。 */
export type ParkingPageMeta = components['schemas']['PageMeta']

export interface ParkingListing {
  id: number
  spaceId: number
  spaceNumber: string
  availableFrom: string
  availableUntil: string | null
  monthlyFee: number
  status: 'ACTIVE' | 'CLOSED'
}

export interface ListingResponse {
  id: number
  spaceId: number
  assignmentId: number
  listedBy: number
  reason: string | null
  desiredTransferDate: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface ListingDetailResponse {
  id: number
  spaceId: number
  assignmentId: number
  listedBy: number
  reason: string | null
  desiredTransferDate: string | null
  status: string
  transfereeUserId: number | null
  transfereeVehicleId: number | null
  transferredAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ParkingSettingsResponse {
  id: number
  scopeType: string
  scopeId: string
  maxSpacesPerUser: number
  maxVisitorReservationsPerDay: number
  visitorReservationMaxDaysAhead: number
  visitorReservationRequiresApproval: boolean
}

export interface ParkingStatsResponse {
  totalSpaces: number
  vacantSpaces: number
  occupiedSpaces: number
  maintenanceSpaces: number
  pendingApplications: number
  activeListings: number
  activeSubleases: number
}

export interface SubleaseResponse {
  id: number
  spaceId: number
  offeredBy: number
  title: string
  pricePerMonth: number
  paymentMethod: string
  availableFrom: string
  availableTo: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface SubleaseDetailResponse {
  id: number
  spaceId: number
  assignmentId: number
  offeredBy: number
  title: string
  description: string | null
  pricePerMonth: number
  paymentMethod: string
  availableFrom: string
  availableTo: string | null
  status: string
  matchedApplicationId: number | null
  createdAt: string
  updatedAt: string
}

export interface SubleasePaymentResponse {
  id: number
  subleaseId: number
  payerUserId: number
  payeeUserId: number
  amount: number
  stripeFee: number
  platformFee: number
  netAmount: number
  billingMonth: string
  status: string
  paidAt: string | null
  createdAt: string
}

export interface SubleaseApplicationResponse {
  id: number
  subleaseId: number
  userId: number
  message: string | null
  status: string
  createdAt: string
}

export interface VisitorReservationResponse {
  id: number
  spaceId: number
  reservedBy: number
  visitorName: string
  visitorPlateNumber: string
  reservedDate: string
  timeFrom: string
  timeTo: string
  purpose: string | null
  adminComment: string | null
  approvedBy: number | null
  approvedAt: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface VisitorRecurringResponse {
  id: number
  userId: number
  spaceId: number
  recurrenceType: string
  dayOfWeek: number | null
  dayOfMonth: number | null
  timeFrom: string
  timeTo: string
  visitorName: string
  visitorPlateNumber: string
}

export interface WatchlistResponse {
  id: number
  userId: number
  spaceId: number | null
  area: string | null
  spaceType: string | null
  createdAt: string
}

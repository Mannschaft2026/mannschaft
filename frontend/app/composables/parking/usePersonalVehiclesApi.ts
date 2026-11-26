import type { VehicleResponse } from '~/types/parking'

export function usePersonalVehiclesApi() {
  const api = useApi()

  // === Personal Vehicles (user scope) ===
  async function getMyVehicles() {
    return api<{ data: VehicleResponse[] }>('/api/v1/users/me/vehicles')
  }

  async function addVehicle(body: {
    vehicleType: 'CAR' | 'MOTORCYCLE' | 'BICYCLE'
    plateNumber: string
    nickname?: string
  }) {
    return api<{ data: VehicleResponse }>('/api/v1/users/me/vehicles', { method: 'POST', body })
  }

  return {
    getMyVehicles,
    addVehicle,
  }
}

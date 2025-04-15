package io.github.takusan23.shizukudensokuapilogger

import android.telephony.AccessNetworkUtils
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma

enum class NetworkGeneration {
    ERROR,
    GSM,
    CDMA,
    LTE,
    NR,
}

val CellIdentity.generation
    get() = when (this) {
        is CellIdentityGsm -> NetworkGeneration.GSM
        is CellIdentityCdma, is CellIdentityWcdma, is CellIdentityTdscdma -> NetworkGeneration.CDMA
        is CellIdentityLte -> NetworkGeneration.LTE
        is CellIdentityNr -> NetworkGeneration.NR
        else -> NetworkGeneration.ERROR
    }

val CellIdentity.arfcn
    get() = when (this) {
        is CellIdentityLte -> this.earfcn
        is CellIdentityNr -> this.nrarfcn
        else -> null
    }

val CellIdentity.band
    get() = when (this) {
        is CellIdentityLte -> this.arfcn?.let { "b" + AccessNetworkUtils.getOperatingBandForEarfcn(it) }
        is CellIdentityNr -> this.arfcn?.let { "n" + AccessNetworkUtils.getOperatingBandForNrarfcn(it) }
        else -> null
    }

val CellIdentity.cellId
    get() = when(this){
        is CellIdentityGsm -> null
        is CellIdentityCdma, is CellIdentityWcdma, is CellIdentityTdscdma -> null
        is CellIdentityLte -> this.pci
        is CellIdentityNr -> this.pci
        else -> null

    }

val CellIdentity.plmn
    get() = this.mccString + this.mncString
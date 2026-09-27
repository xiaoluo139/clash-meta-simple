package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.common.util.createListFromParcelSlice
import com.github.kr328.clash.common.util.writeToParcelSlice
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

/**
 * Runtime statistics of one loaded routing rule.
 *
 * mihomo maintains hit / miss counters for every rule internally; this is the
 * transported view of them.
 */
@Serializable
data class RuleStat(
    val index: Int,
    val type: String,
    val payload: String,
    val proxy: String,
    val disabled: Boolean = false,
    val hitCount: Long = 0,
    val missCount: Long = 0,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<RuleStat> {
        override fun createFromParcel(parcel: Parcel): RuleStat {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<RuleStat?> {
            return arrayOfNulls(size)
        }
    }
}

class RuleStatList(data: List<RuleStat>) : List<RuleStat> by data, Parcelable {
    constructor(parcel: Parcel) : this(RuleStat.createListFromParcelSlice(parcel, 0, 50))

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        writeToParcelSlice(dest, flags)
    }

    companion object CREATOR : Parcelable.Creator<RuleStatList> {
        override fun createFromParcel(parcel: Parcel): RuleStatList {
            return RuleStatList(parcel)
        }

        override fun newArray(size: Int): Array<RuleStatList?> {
            return arrayOfNulls(size)
        }
    }
}
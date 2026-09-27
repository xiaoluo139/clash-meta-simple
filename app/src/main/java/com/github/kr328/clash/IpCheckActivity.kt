package com.github.kr328.clash

import com.github.kr328.clash.design.IpCheckDesign
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class IpCheckActivity : BaseActivity<IpCheckDesign>() {
    override suspend fun main() {
        val design = IpCheckDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive {
                    when (it) {
                        IpCheckDesign.Request.Reload -> design.reload()
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (design?.goBack() == true)
            return

        super.onBackPressed()
    }
}
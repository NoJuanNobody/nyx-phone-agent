# Nyx Phone — Pixel device product makefile
# Inherits from AOSP Pixel base; adds NyxAgent as a privileged system app.

PRODUCT_NAME := nyx_phone
PRODUCT_DEVICE := pixel
PRODUCT_BRAND := Nyx
PRODUCT_MANUFACTURER := Nyx
PRODUCT_MODEL := NyxPhone Pixel

# Inherit AOSP telephony and core packages
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Include NyxAgent as a privileged system app
PRODUCT_PACKAGES += \
    NyxAgent

PRODUCT_COPY_FILES += \
    device/nyx/pixel/init.nyx.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.nyx.rc \
    frameworks/base/core/res/res/xml/privapp-permissions-nyx.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-nyx.xml

PRODUCT_PROPERTY_OVERRIDES += \
    ro.nyx.agent.enabled=1 \
    ro.nyx.agent.debug=0

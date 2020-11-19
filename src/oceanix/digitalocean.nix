{config, pkgs, ...}:
{
  # this does some kind of magic to talk to the hosting environment
  # and inject STUFF into the VM, resize disks, whatever.
  services.cloud-init.enable = true;

  # we should get hooked up by dhcp
  networking.dhcpcd.enable = true;

  # enable virtio modules, since we are in a virtio host.
  boot.initrd.availableKernelModules = [ "virtio_net" "virtio_pci" "virtio_mmio" "virtio_blk" "virtio_scsi" "9p" "9pnet_virtio" ];
  boot.initrd.kernelModules = [ "virtio_balloon" "virtio_console" "virtio_rng" ];

  # Select internationalisation properties.
  i18n.defaultLocale = "en_GB.UTF-8";
  console = {
    font = "Lat2-Terminus16";
    keyMap = "uk";
  };

  time.timeZone = "Europe/London";
  services.openssh.enable = true;

  environment.systemPackages =  [
    pkgs.rxvt_unicode.terminfo
  ];

  nixpkgs.config.allowUnfree = true;
  nix.trustedUsers = [ "@wheel" ];

  imports = [
     (<nixpkgs/nixos> + /maintainers/scripts/openstack/openstack-image.nix)
  ];
}

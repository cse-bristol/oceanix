{config, pkgs, lib, ...}:
{
  imports = [
    <nixpkgs/nixos/modules/profiles/headless.nix>
    <nixpkgs/nixos/modules/virtualisation/digital-ocean-config.nix>
  ];
  
  # we should get hooked up by dhcp
  networking.dhcpcd.enable = true;

  # enable virtio modules, since we are in a virtio host.
  boot.initrd.availableKernelModules = [ "virtio_net" "virtio_pci" "virtio_mmio" "virtio_blk" "virtio_scsi" "9p" "9pnet_virtio" ];
  boot.initrd.kernelModules = [ "virtio_balloon" "virtio_console" "virtio_rng" ];

  services.openssh.permitRootLogin = "prohibit-password";

  networking.hostName = lib.mkDefault "";
  
  nix.trustedUsers = [ "@wheel" ];
}

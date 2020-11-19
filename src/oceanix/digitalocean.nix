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

  services.openssh.enable = true;

  nix.trustedUsers = [ "@wheel" ];

  systemd.services."keys@" = {
    description = "Awaiting presence of key %i";

    enable = true;
    serviceConfig =
      let
        iw = "${pkgs.inotifyTools}/bin/inotifywait";
        until-there = pkgs.writeScript "until-there.sh" ''
          #!${pkgs.bash}/bin/bash
          tgt="/run/keys/$1"
          (while read f; do if [ "$f" = "$1" ]; then break; fi; done \
              < <(${iw} -qm --format '%f' -e create,move /run/keys) ) &

          if [[ -e "$tgt" ]]; then
            kill %1
            exit 0
          fi
          wait %1
        '';
        until-gone = pkgs.writeScript "until-gone-sh" ''
          #!${pkgs.bash}/bin/bash
          tgt="/run/keys/$1"
          ${iw} -qq -e delete_self "$tgt" &
          if [[ ! -e "${keyCfg.path}" ]]; then
             exit 0
          fi
          wait %1
        '';
      in
      {
        TimeoutStartSec = "infinity";
        Restart = "always";
        RestartSec = "500ms";
        ExecStartPre = "${until-there} %i";
        ExecStart = "${until-gone} %i";
      };
  };

  imports = [
     (<nixpkgs/nixos> + /maintainers/scripts/openstack/openstack-image.nix)
  ];
}

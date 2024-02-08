{config, pkgs, ...}:
with pkgs.lib;
{
  options =
    let keyType = types.submodule ({ config, name, ...}:{
      options.name = mkOption {
        type = types.str;
        default = name;
        description = "Key name - resulting key will be /run/keys/<name>";
      };

      options.keyCommand = mkOption {
        default = null;
        example = [ "pass" "show" "secrettoken" ];
        type = types.nullOr (types.listOf types.str);
        description = "Command to use to get the secret, when deploying";
      };
      
      options.keyFile = mkOption {
        default = null;
        type = types.nullOr types.path;
        apply = value: if value == null then null else toString value;
      };

      options.transient = mkOption {
        default = true;
        types = types.boolean;
        description = "Wipe key on reboot if true; restore from a copy on the machine otherwise.";
      };
      
      options.user = mkOption {
        default = "root";
        type = types.str;
        description = ''
          The user which will be the owner of the key file.
        '';
      };

      options.group = mkOption {
        default = "root";
        type = types.str;
        description = ''
          The group that will be set for the key file.
        '';
      };

      options.permissions = mkOption {
        default = "0600";
        example = "0640";
        type = types.str;
        description = ''
          The default permissions to set for the key file, needs to be in the
          format accepted by ``chmod(1)``.
        '';
      };
    });
    in
    {
      deployment.keys = mkOption {
        type = types.attrsOf keyType;
        default = {};
      };
    };

    config = {
      systemd.services."persistent-keys" = {
        description = "Ensure symlinks from /var/keys to /run/keys";
        enable = true;
        script = ''
          cp -nrs /var/keys/. /run/keys/
        '';
        # start on boot.
        wantedBy = ["multi-user.target"];
      };
      systemd.services."keys@" = {
        description = "Awaiting presence of key %i";

        enable = true;
        serviceConfig =
          let
            iw = "${pkgs.inotifyTools}/bin/inotifywait";
            until-there = pkgs.writeScript "until-there.sh" ''
              #!${pkgs.bash}/bin/bash
              tgt="/run/keys/$1"
              echo "Waiting for key $tgt"
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
              echo "Have key $tgt"
              ${iw} -qq -e delete_self "$tgt" &
              if [[ ! -e "$tgt" ]]; then
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

    };
}
